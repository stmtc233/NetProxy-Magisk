package module

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"

	moduleconfig "github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/config"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/inbound"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/logfile"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/paths"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/service"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/serviceapi"
	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/worker"
)

const (
	serviceReadyTimeout = 30 * time.Second
	serviceStopTimeout  = 10 * time.Second
)

var (
	writeServiceState        = WriteServiceState
	terminateServiceForStart = terminateService
)

// ServiceResult 描述一次服务操作结束后的统一状态快照。
type ServiceResult struct {
	Action string         `json:"action"`
	Status service.Status `json:"status"`
}

func toggleServiceAction(state string) (string, error) {
	switch state {
	case "ready":
		return "stop", nil
	case "", "stopped", "failed":
		return "start", nil
	case "preparing", "starting", "stopping":
		return "", fmt.Errorf("服务正在切换 (%s)", state)
	default:
		return "start", nil
	}
}

// ManageService 执行 sing-box 生命周期操作。生命周期锁、状态落盘和进程管理全部在 Go 内完成。
func ManageService(ctx context.Context, options Options, action string) (ServiceResult, error) {
	action = strings.TrimSpace(action)
	if action == "status" {
		return serviceResult(ctx, options, action)
	}
	if action == "check" {
		if err := CheckService(ctx, options); err != nil {
			return ServiceResult{}, err
		}
		return serviceResult(ctx, options, action)
	}
	if action != "start" && action != "stop" && action != "restart" && action != "reload" && action != "toggle" {
		return ServiceResult{}, fmt.Errorf("未知服务操作: %s", action)
	}

	lock, err := acquireLifecycleLock(options.StateFile)
	if err != nil {
		return ServiceResult{}, err
	}
	defer lock.release()

	if action == "toggle" {
		state, stateErr := ReadServiceState(options.StateFile)
		if stateErr != nil {
			return ServiceResult{}, fmt.Errorf("读取服务状态失败: %w", stateErr)
		}
		action, err = toggleServiceAction(state.State)
		if err != nil {
			return ServiceResult{}, err
		}
	}

	switch action {
	case "start":
		err = StartService(ctx, options)
	case "stop":
		err = StopService(ctx, options)
	case "restart":
		if err = StopService(ctx, options); err == nil {
			err = StartService(ctx, options)
		}
	case "reload":
		err = ReloadService(ctx, options)
	}
	if err != nil {
		return ServiceResult{}, err
	}
	if action == "start" || action == "restart" {
		if err := EnsureWorker(ctx, options); err != nil {
			// Worker 独立于 sing-box，不能因为订阅调度启动失败而把已经就绪的核心判为失败。
			logService(options, "WARN", "worker.start", "failed", "后台 Worker 启动失败: %v", err)
		}
	}
	return serviceResult(ctx, options, action)
}

// StartService 生成运行时配置，启动 sing-box，并在控制面及入站就绪后记录实例身份。
func StartService(ctx context.Context, options Options) (err error) {
	if err := validateLifecycleOptions(options); err != nil {
		return err
	}
	if err := recoverConfigApply(ctx, options); err != nil {
		return err
	}
	options, release, err := lockConfigFiles(ctx, options, options.ModuleConfig, options.InboundConfig, paths.SingBoxConfig(options.SingBoxDir))
	if err != nil {
		return err
	}
	defer release()
	state, stateErr := ReadServiceState(options.StateFile)
	if stateErr != nil {
		return fmt.Errorf("读取服务状态失败: %w", stateErr)
	}
	if pid := service.FindProcess(options.SingBoxPath, int(state.PID)); pid > 0 {
		if err := ensureSingBoxRootCgroup(pid); err != nil {
			message := "sing-box 无法加入 root cgroup"
			stateErr := writeServiceState(options.StateFile, "failed", int64(pid), state.StartedAt, 0, message)
			logService(options, "ERROR", "service.cgroup", "failed", "%s: %v", message, err)
			return errors.Join(fmt.Errorf("%s: %w", message, err), stateErr)
		}
		startedAt, err := serviceStartedAtMillis(ctx, options)
		if err != nil {
			message := "检测到无响应的 sing-box 进程"
			stateErr := writeServiceState(options.StateFile, "failed", int64(pid), state.StartedAt, 0, message)
			return errors.Join(fmt.Errorf("%s: %w", message, err), stateErr)
		}
		readyAt := state.ReadyAt
		if readyAt <= 0 {
			readyAt = time.Now().Unix()
		}
		var identity []ServiceIdentity
		if state.PID == int64(pid) && state.CoreStartedAtMillis == startedAt && state.ActiveBackend != "" {
			identity = append(identity, ServiceIdentity{state.ActiveBackend, startedAt})
		}
		if _, err := applyConfiguredNetwork(ctx, options, reloadNetworkConfig); err != nil {
			return err
		}
		// 网络策略可能已经重载实例，不能再写回重载前的启动身份。
		if refreshed, _ := ReadServiceState(options.StateFile); refreshed.CoreStartedAtMillis != state.CoreStartedAtMillis && refreshed.State == "ready" {
			return nil
		}
		if err := writeServiceState(options.StateFile, "ready", int64(pid), startedAt/1000, readyAt, "", identity...); err != nil {
			return err
		}
		logService(options, "WARN", "service.start", "already-running", "sing-box 已在运行 (PID: %d)", pid)
		return nil
	}

	logService(options, "INFO", "service.start", "started", "启动 sing-box 服务")
	if err := writeServiceState(options.StateFile, "preparing", 0, 0, 0, ""); err != nil {
		return err
	}
	prepared, err := Prepare(ctx, options, false)
	if err != nil {
		return failServiceStart(options, 0, 0, "运行时配置生成失败", err)
	}
	if err := checkPreparedConfiguration(ctx, options, prepared); err != nil {
		return failServiceStart(options, 0, 0, "sing-box 配置检查失败", err)
	}
	if err := options.updateSelection(ctx, prepared.Selection); err != nil {
		return failServiceStart(options, 0, 0, "运行时选择状态同步失败", err)
	}
	return startPreparedService(ctx, options, prepared)
}

// 事务内只启动已经准备好的快照，不恢复或重建正在应用的 journal。
func startPreparedService(ctx context.Context, options Options, prepared PrepareResult) (err error) {
	options.networkEvaluation = prepared.Network
	if err := ctx.Err(); err != nil {
		return err
	}
	command, logFile, err := newSingBoxCommand(options, prepared)
	if err != nil {
		return failServiceStart(options, 0, 0, "sing-box 进程启动失败", err)
	}
	attemptedAt := time.Now()
	stage := "launch"
	defer func() {
		if ctx.Err() == nil {
			_ = options.Telemetry.RecordStart(time.Now(), time.Since(attemptedAt), stage, err == nil)
		}
	}()
	if err := command.Start(); err != nil {
		_ = logFile.Close()
		return failServiceStart(options, 0, 0, "sing-box 进程启动失败", err)
	}
	pid := command.Process.Pid
	stage = "cgroup"
	if err := ensureSingBoxRootCgroup(pid); err != nil {
		_ = logFile.Close()
		return failServiceStart(options, pid, 0, "sing-box 无法加入 root cgroup", err)
	}
	_ = command.Process.Release()
	_ = logFile.Close()
	startedAt := time.Now().Unix()
	stage = "state"
	if err := writeServiceState(options.StateFile, "starting", int64(pid), startedAt, 0, ""); err != nil {
		return failServiceStateWrite(options, pid, startedAt, "starting", err)
	}

	stage = "ready"
	actualStartedAt, err := waitForServiceReady(ctx, options, pid, serviceReadyTimeout, 0)
	if err != nil {
		return failServiceStart(options, pid, startedAt, "核心或控制接口未在限定时间内就绪", err)
	}
	startedAt = actualStartedAt / 1000
	stage = "selection"
	if err := syncSelection(ctx, options); err != nil {
		return failServiceStart(options, pid, startedAt, "运行时节点选择同步失败", err)
	}
	stage = "mode"
	if _, err := syncConfiguredMode(ctx, options); err != nil {
		return failServiceStart(options, pid, startedAt, "运行时出站模式同步失败", err)
	}
	readyAt := time.Now().Unix()
	stage = "state"
	if err := writeServiceState(options.StateFile, "ready", int64(pid), startedAt, readyAt, "", ServiceIdentity{prepared.Backend, actualStartedAt}); err != nil {
		return failServiceStateWrite(options, pid, startedAt, "ready", err)
	}
	logService(options, "INFO", "service.start", "success", "sing-box 服务启动完成 (PID: %d)", pid)
	return nil
}

// StopService 终止 sing-box 并清理所有可重建运行时配置。
func StopService(_ context.Context, options Options) error {
	if err := ensureLifecycleStateDir(options); err != nil {
		return err
	}
	state, stateErr := ReadServiceState(options.StateFile)
	if stateErr != nil {
		state = ServiceState{Schema: 1, State: "stopped"}
	}
	pid := service.FindProcess(options.SingBoxPath, int(state.PID))
	logService(options, "INFO", "service.stop", "started", "停止 sing-box 服务")
	stateWriteErr := writeServiceState(options.StateFile, "stopping", int64(pid), state.StartedAt, 0, "")
	if pid > 0 {
		if err := terminateService(options, pid); err != nil {
			message := "sing-box 进程停止失败"
			failedWriteErr := writeServiceState(options.StateFile, "failed", int64(pid), state.StartedAt, 0, message)
			return errors.Join(fmt.Errorf("%s: %w", message, err), stateErr, stateWriteErr, failedWriteErr)
		}
	}
	cleanupRuntimeFiles(options)
	stoppedWriteErr := writeServiceState(options.StateFile, "stopped", 0, 0, 0, "")
	if err := errors.Join(stateErr, stateWriteErr, stoppedWriteErr); err != nil {
		return fmt.Errorf("停止 sing-box 已完成，但服务状态写入失败: %w", err)
	}
	logService(options, "INFO", "service.stop", "success", "sing-box 服务已停止")
	return nil
}

// ReloadService 原位重载已运行实例，并等待 Service API 报告新的启动时间。
func ReloadService(ctx context.Context, options Options) error {
	if err := validateLifecycleOptions(options); err != nil {
		return err
	}
	if err := recoverConfigApply(ctx, options); err != nil {
		return err
	}
	options, release, err := lockConfigFiles(ctx, options, options.ModuleConfig, options.InboundConfig, paths.SingBoxConfig(options.SingBoxDir))
	if err != nil {
		return err
	}
	defer release()
	state, stateErr := ReadServiceState(options.StateFile)
	if stateErr != nil {
		return fmt.Errorf("读取服务状态失败: %w", stateErr)
	}
	pid := service.FindProcess(options.SingBoxPath, int(state.PID))
	if pid <= 0 {
		return errors.New("sing-box 未运行，无法重新加载")
	}
	if _, err := serviceStartedAtMillis(ctx, options); err != nil {
		return fmt.Errorf("Service API 未就绪，无法确认原位重新加载: %w", err)
	}
	configured, err := inbound.Load(options.InboundConfig)
	if err != nil {
		return err
	}
	if state.ActiveBackend != "" && state.ActiveBackend != configured.Backend {
		return &inbound.ValidationError{Diagnostics: []inbound.Diagnostic{{Level: "error", Code: "inbound.restart_required", Message: "运行后端与配置不同，请通过入站设置切换或重启服务"}}}
	}
	logService(options, "INFO", "service.reload", "started", "重新加载 sing-box 配置")
	prepared, err := Prepare(ctx, options, false)
	if err != nil {
		return fmt.Errorf("重新加载配置生成失败: %w", err)
	}
	if err := checkPreparedConfiguration(ctx, options, prepared); err != nil {
		return err
	}
	return reloadPreparedService(ctx, options, prepared, true)
}

func reloadAppliedConfig(ctx context.Context, options Options) error {
	prepared, err := Prepare(ctx, options, false)
	if err != nil {
		return fmt.Errorf("重新加载配置生成失败: %w", err)
	}
	if err := checkPreparedConfiguration(ctx, options, prepared); err != nil {
		return err
	}
	return reloadPreparedService(ctx, options, prepared, true)
}

func startAppliedConfig(ctx context.Context, options Options) error {
	prepared, err := Prepare(ctx, options, false)
	if err != nil {
		return err
	}
	if err := checkPreparedConfiguration(ctx, options, prepared); err != nil {
		return err
	}
	if err := options.updateSelection(ctx, prepared.Selection); err != nil {
		return err
	}
	return startPreparedService(ctx, options, prepared)
}

func reloadConfigSnapshot(ctx context.Context, options Options, journal configApplyJournal) error {
	prepared := prepareFromConfigJournal(options, journal)
	for name, path := range map[string]string{
		"providers": prepared.Providers,
		"outbounds": prepared.Outbounds,
		"inbound":   prepared.Inbound,
	} {
		if path == "" {
			return fmt.Errorf("旧运行时快照缺少 %s", name)
		}
		if _, err := os.Stat(path); err != nil {
			return fmt.Errorf("旧运行时快照 %s 不可用: %w", name, err)
		}
	}
	if !service.ProcessRunning(options.SingBoxPath) {
		return startPreparedService(ctx, options, prepared)
	}
	return reloadPreparedService(ctx, options, prepared, false)
}

func reloadPreparedService(ctx context.Context, options Options, prepared PrepareResult, synchronizeSelection bool) error {
	options.networkEvaluation = prepared.Network
	state, _ := ReadServiceState(options.StateFile)
	pid := service.FindProcess(options.SingBoxPath, int(state.PID))
	if pid <= 0 {
		return errors.New("sing-box 未运行，无法重新加载")
	}
	oldStartedAtMillis, err := serviceStartedAt(ctx, options)
	if err != nil {
		return fmt.Errorf("Service API 未就绪，无法确认原位重新加载: %w", err)
	}
	if err := waitCoreSettled(ctx, options, pid, oldStartedAtMillis); err != nil {
		if errors.Is(err, errServiceProcessExited) {
			return restartExitedService(ctx, options, prepared, synchronizeSelection, err)
		}
		return fmt.Errorf("无法重新加载: %w", err)
	}
	oldStartedAt := oldStartedAtMillis / 1000
	if err := writeServiceState(options.StateFile, "starting", int64(pid), oldStartedAt, 0, ""); err != nil {
		return err
	}
	if err := signalReload(pid); err != nil {
		return restoreReloadState(ctx, options, pid, oldStartedAt, state.ReadyAt, err)
	}
	startedAt, err := waitForServiceReady(ctx, options, pid, serviceReadyTimeout, oldStartedAtMillis)
	if errors.Is(err, errServiceProcessExited) {
		return restartExitedService(ctx, options, prepared, synchronizeSelection, err)
	}
	if err != nil {
		return restoreReloadState(ctx, options, pid, oldStartedAt, state.ReadyAt, err)
	}
	if synchronizeSelection {
		if err := options.updateSelection(ctx, prepared.Selection); err != nil {
			return restoreReloadState(ctx, options, pid, startedAt/1000, state.ReadyAt, err)
		}
		if err := syncSelection(ctx, options); err != nil {
			return restoreReloadState(ctx, options, pid, startedAt/1000, state.ReadyAt, err)
		}
	}
	if _, err := syncConfiguredMode(ctx, options); err != nil {
		return restoreReloadState(ctx, options, pid, startedAt/1000, state.ReadyAt, err)
	}
	if err := writeServiceState(options.StateFile, "ready", int64(pid), startedAt/1000, time.Now().Unix(), "", ServiceIdentity{prepared.Backend, startedAt}); err != nil {
		return err
	}
	logService(options, "INFO", "service.reload", "success", "sing-box 配置重新加载完成")
	return nil
}

// restartExitedService 在实例于重载期间自行退出后，用已通过检查的同一份配置冷启动。
// 内核关闭旧实例超过 10 秒会以 "did not close!" 退出；若只记录 failed，订阅更新与节点变更
// 这类没有配置回滚的调用方会让核心一直停止，直到用户手动启动。
func restartExitedService(ctx context.Context, options Options, prepared PrepareResult, synchronizeSelection bool, cause error) error {
	logService(options, "WARN", "service.reload", "restarting", "sing-box 在重新加载期间退出，使用新配置重新启动: %v", cause)
	if synchronizeSelection {
		if err := options.updateSelection(ctx, prepared.Selection); err != nil {
			return restoreReloadState(ctx, options, 0, 0, 0, errors.Join(cause, err))
		}
	}
	if err := startExitedService(ctx, options, prepared); err != nil {
		return fmt.Errorf("sing-box 原位重新加载失败: %w", errors.Join(cause, err))
	}
	return nil
}

// CheckService 在隔离运行时目录中检查完整 sing-box 配置，不影响正在运行的实例。
func CheckService(ctx context.Context, options Options) error {
	if err := validateLifecycleOptions(options); err != nil {
		return err
	}
	lock, err := acquireLifecycleLock(options.StateFile)
	if err != nil {
		return err
	}
	defer lock.release()
	if err := recoverConfigApply(ctx, options); err != nil {
		return err
	}
	options, release, err := lockConfigFiles(ctx, options, options.ModuleConfig, options.InboundConfig, paths.SingBoxConfig(options.SingBoxDir))
	if err != nil {
		return err
	}
	defer release()
	temporary, err := os.MkdirTemp(filepath.Dir(options.StateFile), "config-check-")
	if err != nil {
		return fmt.Errorf("创建配置检查目录失败: %w", err)
	}
	defer os.RemoveAll(temporary)
	checkOptions := options
	checkOptions.RuntimeDir = temporary
	prepared, err := Prepare(ctx, checkOptions, true)
	if err != nil {
		return err
	}
	return checkPreparedConfiguration(ctx, checkOptions, prepared)
}

// Boot 承载 service 阶段的最小开机流程：等待系统、按配置启动服务并拉起唯一 Worker。
func Boot(ctx context.Context, options Options) error {
	if err := os.MkdirAll(options.LogDir, 0o700); err != nil {
		return err
	}
	logService(options, "INFO", "module.boot", "started", "NetProxy 开机服务启动")
	logService(options, "INFO", "module.boot", "waiting", "等待 Android 启动完成")
	if err := newBootCompletedWaitCommand(ctx).Run(); err != nil {
		return fmt.Errorf("等待系统启动完成失败: %w", err)
	}
	logService(options, "INFO", "module.boot", "ready", "Android 启动完成")
	if err := EnsureWorker(ctx, options); err != nil {
		logService(options, "WARN", "worker.start", "failed", "后台 Worker 启动失败，可稍后手动重试: %v", err)
	}
	config, err := moduleconfig.LoadModule(options.ModuleConfig)
	if err != nil {
		return fmt.Errorf("加载模块配置失败: %w", err)
	}
	if config.AutoStart {
		if _, err := ManageService(ctx, options, "start"); err != nil {
			logService(options, "WARN", "service.start", "failed", "代理服务开机启动失败，可在导入节点或修正配置后手动启动: %v", err)
		}
	} else {
		logService(options, "INFO", "service.start", "skipped", "开机自启动已禁用，跳过启动")
	}
	logService(options, "INFO", "module.boot", "success", "开机服务流程结束")
	return nil
}

func newBootCompletedWaitCommand(ctx context.Context) *exec.Cmd {
	return exec.CommandContext(ctx, "resetprop", "-w", "sys.boot_completed", "0")
}

func EnsureWorker(ctx context.Context, options Options) error {
	executable := paths.New(options.ModuleDir).Executable()
	if _, err := worker.Start(ctx, workerOptions(options), executable); err != nil {
		return err
	}
	return nil
}

func serviceResult(ctx context.Context, options Options, action string) (ServiceResult, error) {
	status, err := service.ReadStatus(ctx, networkControlOptions(options))
	if err != nil {
		return ServiceResult{}, err
	}
	return ServiceResult{Action: action, Status: status}, nil
}

func validateLifecycleOptions(options Options) error {
	if err := options.validate(); err != nil {
		return err
	}
	for name, path := range map[string]string{
		"sing-box 二进制": options.SingBoxPath,
		"sing-box 主配置": paths.SingBoxConfig(options.SingBoxDir),
		"Catalog":      options.CatalogRoot,
	} {
		info, err := os.Stat(path)
		if err != nil {
			return fmt.Errorf("%s不可用: %w", name, err)
		}
		if name == "Catalog" && !info.IsDir() {
			return fmt.Errorf("%s不是目录: %s", name, path)
		}
		if name != "Catalog" && !info.Mode().IsRegular() {
			return fmt.Errorf("%s不是普通文件: %s", name, path)
		}
	}
	if _, err := os.Stat(options.InboundConfig); err != nil {
		return fmt.Errorf("入站配置不可用: %w", err)
	}
	return ensureLifecycleStateDir(options)
}

func ensureLifecycleStateDir(options Options) error {
	if err := os.MkdirAll(filepath.Dir(options.StateFile), 0o700); err != nil {
		return err
	}
	if err := os.Chmod(filepath.Dir(options.StateFile), 0o700); err != nil {
		return err
	}
	return os.MkdirAll(options.LogDir, 0o700)
}

func newSingBoxCommand(options Options, prepared PrepareResult) (*exec.Cmd, *os.File, error) {
	logPath, err := LogFile(options, "core")
	if err != nil {
		return nil, nil, err
	}
	logFile, err := logfile.OpenForProcess(logPath)
	if err != nil {
		return nil, nil, err
	}
	command := exec.Command(options.SingBoxPath, "run", "--disable-color", "-c", paths.SingBoxConfig(options.SingBoxDir),
		"-c", prepared.Providers, "-c", prepared.Outbounds, "-c", prepared.Inbound)
	command.Dir = options.SingBoxDir
	command.Stdout = logFile
	command.Stderr = logFile
	detachServiceCommand(command)
	return command, logFile, nil
}

func checkPreparedConfiguration(ctx context.Context, options Options, prepared PrepareResult) error {
	command := exec.CommandContext(ctx, options.SingBoxPath, "check", "--disable-color", "-c", paths.SingBoxConfig(options.SingBoxDir),
		"-c", prepared.Providers, "-c", prepared.Outbounds, "-c", prepared.Inbound)
	command.Dir = options.SingBoxDir
	command.Stdout = os.Stderr
	command.Stderr = os.Stderr
	if err := command.Run(); err != nil {
		return fmt.Errorf("sing-box 配置检查失败: %w", err)
	}
	return nil
}

func serviceStartedAtMillis(ctx context.Context, options Options) (int64, error) {
	client, err := serviceapi.New(options.ServiceAddress, options.ServiceSecret)
	if err != nil {
		return 0, err
	}
	defer client.Close()
	requestContext, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	startedAt, err := client.Ready(requestContext)
	if err != nil {
		return 0, err
	}
	if startedAt.UnixMilli <= 0 {
		return 0, errors.New("Service API 返回的启动时间无效")
	}
	return startedAt.UnixMilli, nil
}

// 以下为测试注入点。
var (
	coreStartFinished  = probeCoreStartFinished
	signalReload       = signalServiceReload
	serviceStartedAt   = serviceStartedAtMillis
	startExitedService = startPreparedService
)

var errServiceProcessExited = errors.New("sing-box 进程已退出")

const (
	// 未配置 Clash API 时，Service API 启动时间稳定这么久才视为核心启动完成。
	serviceStartSettle = 1500 * time.Millisecond
	// 重载前等待上一个实例完成启动的上限。
	reloadGuardTimeout = 15 * time.Second
)

// probeCoreStartFinished 判断 sing-box 是否已越过 Start() 的最后阶段。
// Service API 在 finish-start 早期就监听，启动时间变化只说明 API 已启动；此时再收到 SIGHUP，
// 内核会取消启动上下文并以 "start service: context canceled" 退出。
// Clash API 在内部服务 finish-start 末尾才监听，其可连接即代表启动完成；未配置时退化为启动时间稳定窗口。
func probeCoreStartFinished(ctx context.Context, controller string, startedAtMillis int64) bool {
	if controller == "" {
		return time.Since(time.UnixMilli(startedAtMillis)) >= serviceStartSettle
	}
	requestContext, cancel := context.WithTimeout(ctx, time.Second)
	defer cancel()
	request, err := http.NewRequestWithContext(requestContext, http.MethodGet, "http://"+controller+"/version", nil)
	if err != nil {
		return false
	}
	client := &http.Client{
		Transport:     &http.Transport{Proxy: nil, DisableKeepAlives: true},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	defer client.CloseIdleConnections()
	response, err := client.Do(request)
	if err != nil {
		return false
	}
	_ = response.Body.Close()
	// 任意 HTTP 响应（含未授权）都说明监听已建立，不携带密钥。
	return true
}

func loadClashController(options Options) string {
	controller, err := moduleconfig.LoadClashController(paths.SingBoxConfig(options.SingBoxDir))
	if err != nil {
		return ""
	}
	return controller
}

func waitForServiceReady(ctx context.Context, options Options, pid int, timeout time.Duration, previousStartedAtMillis int64) (int64, error) {
	controller := loadClashController(options)
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	var startedAt int64
	for {
		if !serviceProcessAlive(pid) {
			return 0, errServiceProcessExited
		}
		if current, err := serviceStartedAt(ctx, options); err == nil {
			// 启动期间内核自行重载会再次改变启动时间，以最新值为准重新等待。
			if current != startedAt && (startedAt != 0 || previousStartedAtMillis == 0 || current != previousStartedAtMillis) {
				startedAt = current
			}
			if startedAt != 0 && coreStartFinished(ctx, controller, startedAt) {
				return startedAt, nil
			}
		}
		select {
		case <-ctx.Done():
			return 0, ctx.Err()
		case <-deadline.C:
			return 0, errors.New("核心或控制接口未在限定时间内就绪")
		case <-ticker.C:
		}
	}
}

// waitCoreSettled 在发送重载信号前确认当前实例已完成启动，避免 SIGHUP 打断仍在启动的实例。
func waitCoreSettled(ctx context.Context, options Options, pid int, startedAtMillis int64) error {
	controller := loadClashController(options)
	deadline := time.NewTimer(reloadGuardTimeout)
	defer deadline.Stop()
	ticker := time.NewTicker(200 * time.Millisecond)
	defer ticker.Stop()
	for !coreStartFinished(ctx, controller, startedAtMillis) {
		if !serviceProcessAlive(pid) {
			return errServiceProcessExited
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-deadline.C:
			return errors.New("sing-box 仍在启动，暂不发送重载信号")
		case <-ticker.C:
		}
	}
	return nil
}

var ErrForcedTermination = errors.New("sing-box 已强制终止，无法确认入站及路由清理，请重启设备后重试")

func terminateService(options Options, pid int) error {
	if pid <= 0 || !serviceProcessAlive(pid) {
		return nil
	}
	if err := signalServiceStop(pid); err != nil {
		return err
	}
	deadline := time.NewTimer(serviceStopTimeout)
	defer deadline.Stop()
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for serviceProcessAlive(pid) {
		select {
		case <-deadline.C:
			logService(options, "WARN", "service.stop", "forced", "sing-box 未在限定时间内退出，改用 SIGKILL")
			if err := signalServiceKill(pid); err != nil {
				return err
			}
			return errors.Join(ErrForcedTermination, waitServiceExit(pid, time.Second))
		case <-ticker.C:
		}
	}
	return nil
}

func waitServiceExit(pid int, timeout time.Duration) error {
	deadline := time.NewTimer(timeout)
	defer deadline.Stop()
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for serviceProcessAlive(pid) {
		select {
		case <-deadline.C:
			return fmt.Errorf("sing-box 未在限定时间内退出: %d", pid)
		case <-ticker.C:
		}
	}
	return nil
}

func failServiceStart(options Options, pid int, startedAt int64, message string, cause error) error {
	var stopErr error
	if pid > 0 {
		stopErr = terminateServiceForStart(options, pid)
	}
	cleanupRuntimeFiles(options)
	stateErr := writeServiceState(options.StateFile, "failed", int64(pid), startedAt, 0, message)
	logService(options, "ERROR", "service.start", "failed", "%s: %v", message, cause)
	var convergeErr error
	if stateErr != nil {
		if err := os.Remove(options.StateFile); err != nil && !os.IsNotExist(err) {
			convergeErr = fmt.Errorf("清理未收敛的服务状态失败: %w", err)
		}
	}
	return errors.Join(fmt.Errorf("%s: %w", message, cause), stopErr, stateErr, convergeErr)
}

func failServiceStateWrite(options Options, pid int, startedAt int64, state string, cause error) error {
	return failServiceStart(options, pid, startedAt, fmt.Sprintf("写入 sing-box %s 状态失败", state), cause)
}

func restoreReloadState(_ context.Context, options Options, pid int, startedAt, _ int64, cause error) error {
	stateErr := writeServiceState(options.StateFile, "failed", int64(pid), startedAt, 0, cause.Error())
	cause = errors.Join(cause, stateErr)
	logService(options, "ERROR", "service.reload", "failed", "sing-box 原位重新加载失败: %v", cause)
	return fmt.Errorf("sing-box 原位重新加载失败: %w", cause)
}

func cleanupRuntimeFiles(options Options) {
	for _, name := range []string{"providers.json", "outbounds.json", "inbound.json"} {
		_ = os.Remove(filepath.Join(options.RuntimeDir, name))
	}
}

func logService(options Options, level, event, result, format string, args ...any) {
	logEvent(options, level, "service", event, result, format, args...)
}

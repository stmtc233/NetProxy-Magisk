package module

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestProbeCoreStartFinished(t *testing.T) {
	ctx := context.Background()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer server.Close()
	if !probeCoreStartFinished(ctx, strings.TrimPrefix(server.URL, "http://"), time.Now().UnixMilli()) {
		t.Fatal("Clash API 已监听（含 401）应视为启动完成")
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	address := listener.Addr().String()
	listener.Close()
	if probeCoreStartFinished(ctx, address, time.Now().UnixMilli()) {
		t.Fatal("Clash API 未监听不应视为启动完成")
	}
	if probeCoreStartFinished(ctx, "", time.Now().UnixMilli()) {
		t.Fatal("未配置 Clash API 时启动时间尚未稳定不应视为完成")
	}
	if !probeCoreStartFinished(ctx, "", time.Now().Add(-2*serviceStartSettle).UnixMilli()) {
		t.Fatal("未配置 Clash API 时启动时间稳定后应视为完成")
	}
}

func TestWaitCoreSettledDoesNotWaitForeverWhileStarting(t *testing.T) {
	original := coreStartFinished
	defer func() { coreStartFinished = original }()
	calls := 0
	coreStartFinished = func(context.Context, string, int64) bool { calls++; return calls >= 3 }
	if err := waitCoreSettled(context.Background(), Options{}, os.Getpid(), 1); err != nil {
		t.Fatal(err)
	}
	if calls != 3 {
		t.Fatalf("应轮询到启动完成: %d", calls)
	}
}

func TestWaitCoreSettledFailsWhenProcessExits(t *testing.T) {
	original := coreStartFinished
	defer func() { coreStartFinished = original }()
	coreStartFinished = func(context.Context, string, int64) bool { return false }
	if err := waitCoreSettled(context.Background(), Options{}, 0x7ffffff0, 1); err == nil {
		t.Fatal("进程已退出应返回错误")
	}
}

func TestReloadRestartsServiceThatExitsDuringReload(t *testing.T) {
	if _, err := os.Stat("/proc/self/exe"); err != nil {
		t.Skip("需要 /proc 识别核心进程")
	}
	sleep, err := exec.LookPath("sleep")
	if err != nil {
		t.Skip("缺少 sleep")
	}
	root := t.TempDir()
	options := newTestOptions(filepath.Join(root, "module"))
	options.SingBoxPath = filepath.Join(root, "sing-box")
	content, err := os.ReadFile(sleep)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(options.SingBoxPath, content, 0o700); err != nil {
		t.Fatal(err)
	}
	command := exec.Command(options.SingBoxPath, "60")
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = command.Process.Kill(); _ = command.Wait() }()
	if err := os.MkdirAll(filepath.Dir(options.StateFile), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := WriteServiceState(options.StateFile, "ready", int64(command.Process.Pid), 1, 1, ""); err != nil {
		t.Fatal(err)
	}

	originalStartedAt, originalFinished, originalSignal, originalStart := serviceStartedAt, coreStartFinished, signalReload, startExitedService
	defer func() {
		serviceStartedAt, coreStartFinished, signalReload, startExitedService = originalStartedAt, originalFinished, originalSignal, originalStart
	}()
	serviceStartedAt = func(context.Context, Options) (int64, error) { return 1000, nil }
	coreStartFinished = func(context.Context, string, int64) bool { return true }
	// 模拟内核关闭旧实例超时后以 "did not close!" 自行退出。
	signalReload = func(int) error { return command.Process.Kill() }
	var restarted []PrepareResult
	startExitedService = func(_ context.Context, _ Options, prepared PrepareResult) error {
		restarted = append(restarted, prepared)
		return nil
	}

	prepared := PrepareResult{Backend: "ebpf"}
	if err := reloadPreparedService(context.Background(), options, prepared, false); err != nil {
		t.Fatalf("核心在重载中退出后应以新配置重新启动: %v", err)
	}
	if len(restarted) != 1 || restarted[0].Backend != "ebpf" {
		t.Fatalf("应恰好用本次准备的配置重新启动一次: %+v", restarted)
	}
}

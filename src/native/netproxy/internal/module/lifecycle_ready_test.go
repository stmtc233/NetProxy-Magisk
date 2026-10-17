package module

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
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

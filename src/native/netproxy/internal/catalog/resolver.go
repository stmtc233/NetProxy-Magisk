package catalog

import (
	"context"
	"fmt"
	"path/filepath"
	"strings"

	"github.com/Fanju6/NetProxy-Magisk/src/native/netproxy/internal/provider"
	"github.com/sagernet/sing-box/option"
)

// prepareRuntimeProviders 为覆盖节点域名 DNS 的分组生成隔离副本，不修改持久 Provider。
func prepareRuntimeProviders(ctx context.Context, runtimeRoot string, groups []*loadedGroup) error {
	for _, group := range groups {
		group.ResolvedPath = group.ProviderPath
		group.RuntimePath = group.ProviderPath
		serverDNS := strings.TrimSpace(group.Metadata.ServerDNS)
		if serverDNS == "" {
			continue
		}
		document, err := provider.Load(ctx, group.ProviderPath)
		if err != nil {
			return fmt.Errorf("读取分组 %s Provider 以应用节点 DNS: %w", group.ID, err)
		}
		applyDomainResolver(document.Outbounds, document.Endpoints, serverDNS)
		group.ResolvedPath = filepath.Join(runtimeRoot, "providers", group.ID+".json")
		group.RuntimePath = group.ResolvedPath
		if err := provider.SaveAtomic(ctx, group.ResolvedPath, document); err != nil {
			return fmt.Errorf("生成分组 %s 运行时 Provider: %w", group.ID, err)
		}
	}
	return nil
}

func applyDomainResolver(outbounds []option.Outbound, endpoints []option.Endpoint, serverDNS string) {
	for index := range outbounds {
		if wrapper, ok := outbounds[index].Options.(option.DialerOptionsWrapper); ok {
			dialer := wrapper.TakeDialerOptions()
			dialer.DomainResolver = &option.DomainResolveOptions{Server: serverDNS}
			wrapper.ReplaceDialerOptions(dialer)
		}
	}
	for index := range endpoints {
		if wrapper, ok := endpoints[index].Options.(option.DialerOptionsWrapper); ok {
			dialer := wrapper.TakeDialerOptions()
			dialer.DomainResolver = &option.DomainResolveOptions{Server: serverDNS}
			wrapper.ReplaceDialerOptions(dialer)
		}
	}
}

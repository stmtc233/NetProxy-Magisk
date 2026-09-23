package com.fanjv.netproxy.feature.kernel.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fanjv.netproxy.R
import com.fanjv.netproxy.core.di.netProxyViewModel
import com.fanjv.netproxy.core.ui.component.AdaptiveTopAppBar
import com.fanjv.netproxy.core.ui.component.AppSnackbarHost
import com.fanjv.netproxy.core.ui.component.BackIconButton
import com.fanjv.netproxy.core.ui.component.BlurredBar
import com.fanjv.netproxy.core.ui.component.CardItem
import com.fanjv.netproxy.core.ui.component.EmptyCatalog
import com.fanjv.netproxy.core.ui.component.SnackbarNoticeEffect
import com.fanjv.netproxy.core.ui.component.groupedCardSection
import com.fanjv.netproxy.core.ui.component.rememberAppSnackbarHostState
import com.fanjv.netproxy.core.ui.component.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 仅提供三类解析策略；完整 DNS 配置仍由 JSON 编辑器管理。 */
@Composable
internal fun DnsStrategyScreen(
    onBack: () -> Unit,
    viewModel: DnsStrategyViewModel = netProxyViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = rememberAppSnackbarHostState()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val labels = listOf(
        R.string.dns_strategy_default,
        R.string.dns_strategy_prefer_ipv4,
        R.string.dns_strategy_prefer_ipv6,
        R.string.dns_strategy_ipv4_only,
        R.string.dns_strategy_ipv6_only,
    ).map { stringResource(it) }

    LaunchedEffect(Unit) { viewModel.load() }
    SnackbarNoticeEffect(
        eventId = state.noticeId,
        message = if (state.saved) stringResource(R.string.dns_strategy_saved) else state.error,
        isError = !state.saved,
        hostState = snackbar,
        onConsumed = viewModel::clearNotice,
    )

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            BlurredBar(backdrop) {
                AdaptiveTopAppBar(
                    color = if (backdrop != null) Color.Transparent else colorScheme.surface,
                    title = stringResource(R.string.dns_strategy_title),
                    navigationIcon = { BackIconButton(onClick = onBack) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(enabled = state.document != null && !state.saving, onClick = viewModel::save) {
                            if (state.saving) InfiniteProgressIndicator()
                            else Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.save_text))
                        }
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()
            .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
            val document = state.document
            if (document == null) {
                Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    if (state.loading) InfiniteProgressIndicator()
                    else EmptyCatalog(
                        text = stringResource(R.string.singbox_documents_load_failed),
                        onRefresh = viewModel::load,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().fillMaxWidth()
                        .scrollEndHaptic().overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection)
                        .padding(horizontal = 12.dp),
                    contentPadding = innerPadding,
                    overscrollEffect = null,
                ) {
                    groupedCardSection(
                        keyPrefix = "dns_strategy",
                        title = { stringResource(R.string.dns_strategy_title) },
                        items = listOf(
                            CardItem("remote") {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.dns_remote_strategy),
                                    items = labels,
                                    selectedIndex = DnsStrategyDocument.strategies.indexOf(document.remote).coerceAtLeast(0),
                                    onSelectedIndexChange = { index ->
                                        viewModel.update { it.copy(remote = DnsStrategyDocument.strategies[index]) }
                                    },
                                )
                            },
                            CardItem("direct") {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.dns_direct_strategy),
                                    items = labels,
                                    selectedIndex = DnsStrategyDocument.strategies.indexOf(document.direct).coerceAtLeast(0),
                                    onSelectedIndexChange = { index ->
                                        viewModel.update { it.copy(direct = DnsStrategyDocument.strategies[index]) }
                                    },
                                )
                            },
                            CardItem("node") {
                                OverlayDropdownPreference(
                                    title = stringResource(R.string.dns_node_strategy),
                                    items = labels,
                                    selectedIndex = DnsStrategyDocument.strategies.indexOf(document.node).coerceAtLeast(0),
                                    onSelectedIndexChange = { index ->
                                        viewModel.update { it.copy(node = DnsStrategyDocument.strategies[index]) }
                                    },
                                )
                            },
                        ),
                    )
                }
            }
        }
    }
}

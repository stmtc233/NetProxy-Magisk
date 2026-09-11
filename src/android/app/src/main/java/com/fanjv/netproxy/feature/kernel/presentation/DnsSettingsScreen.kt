package com.fanjv.netproxy.feature.kernel.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import com.fanjv.netproxy.core.ui.component.groupedCardItems
import com.fanjv.netproxy.core.ui.component.groupedCardSection
import com.fanjv.netproxy.core.ui.component.rememberAppSnackbarHostState
import com.fanjv.netproxy.core.ui.component.rememberBlurBackdrop
import com.fanjv.netproxy.navigation.LocalNavigator
import com.fanjv.netproxy.navigation.Route
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun DnsSettingsScreen(
    onBack: () -> Unit,
    viewModel: DnsSettingsViewModel = netProxyViewModel(),
) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = rememberAppSnackbarHostState()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    var editingServer by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) { viewModel.load() }
    SnackbarNoticeEffect(
        eventId = state.noticeId,
        message = if (state.saved) stringResource(R.string.dns_settings_saved) else state.error,
        isError = !state.saved,
        hostState = snackbarHostState,
        onConsumed = viewModel::clearNotice,
    )

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            BlurredBar(backdrop) {
                AdaptiveTopAppBar(
                    color = barColor,
                    title = stringResource(R.string.dns_settings),
                    navigationIcon = { BackIconButton(onClick = onBack) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(
                            enabled = !state.loading && !state.saving && state.document != null,
                            onClick = viewModel::save,
                        ) {
                            if (state.saving) {
                                InfiniteProgressIndicator(modifier = Modifier.size(20.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = stringResource(R.string.save_text),
                                )
                            }
                        }
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            when {
                state.loading && state.document == null -> Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { InfiniteProgressIndicator() }

                state.document == null -> Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyCatalog(
                        text = stringResource(R.string.dns_settings_load_failed),
                        onRefresh = viewModel::load,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }

                else -> DnsSettingsContent(
                    document = state.document!!,
                    editingServer = editingServer,
                    onEditingServerChange = { editingServer = it },
                    onUpdate = viewModel::update,
                    onUpdateServer = viewModel::updateServer,
                    onAddServer = {
                        viewModel.addServer()
                        editingServer = state.document!!.servers.size
                    },
                    onRemoveServer = { index ->
                        viewModel.removeServer(index)
                        editingServer = -1
                    },
                    onOpenAdvanced = { navigator.push(Route.JsonEdit("singbox/dns")) },
                    modifier = Modifier.padding(innerPadding),
                    nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                )
            }
        }
    }
}

@Composable
private fun DnsSettingsContent(
    document: DnsSettingsDocument,
    editingServer: Int,
    onEditingServerChange: (Int) -> Unit,
    onUpdate: ((DnsSettingsDocument) -> DnsSettingsDocument) -> Unit,
    onUpdateServer: (Int, (DnsServerDraft) -> DnsServerDraft) -> Unit,
    onAddServer: () -> Unit,
    onRemoveServer: (Int) -> Unit,
    onOpenAdvanced: () -> Unit,
    modifier: Modifier,
    nestedScrollConnection: NestedScrollConnection,
) {
    val strategyLabels = listOf(
        stringResource(R.string.dns_strategy_default),
        stringResource(R.string.dns_strategy_prefer_ipv4),
        stringResource(R.string.dns_strategy_prefer_ipv6),
        stringResource(R.string.dns_strategy_ipv4_only),
        stringResource(R.string.dns_strategy_ipv6_only),
    )
    val finalValues = listOf("") + document.serverTags
    val finalLabels = listOf(stringResource(R.string.dns_final_default)) + document.serverTags

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(nestedScrollConnection)
            .padding(horizontal = 12.dp),
        overscrollEffect = null,
    ) {
        groupedCardSection(
            keyPrefix = "dns_common",
            title = { stringResource(R.string.dns_common_settings) },
            items = listOf(
                CardItem("final") {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.dns_final_server),
                        items = finalLabels,
                        selectedIndex = finalValues.indexOf(document.finalServer).coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            onUpdate { it.copy(finalServer = finalValues[index]) }
                        },
                    )
                },
                CardItem("strategy") {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.dns_strategy),
                        items = strategyLabels,
                        selectedIndex = DnsSettingsDocument.strategies.indexOf(document.strategy)
                            .coerceAtLeast(0),
                        onSelectedIndexChange = { index ->
                            onUpdate { it.copy(strategy = DnsSettingsDocument.strategies[index]) }
                        },
                    )
                },
                CardItem("optimistic") {
                    SwitchPreference(
                        title = stringResource(R.string.dns_optimistic),
                        checked = document.optimistic,
                        onCheckedChange = { value -> onUpdate { it.copy(optimistic = value) } },
                    )
                },
                CardItem("reverse_mapping") {
                    SwitchPreference(
                        title = stringResource(R.string.dns_reverse_mapping),
                        checked = document.reverseMapping,
                        onCheckedChange = { value -> onUpdate { it.copy(reverseMapping = value) } },
                    )
                },
                CardItem("disable_cache") {
                    SwitchPreference(
                        title = stringResource(R.string.dns_disable_cache),
                        checked = document.disableCache,
                        onCheckedChange = { value -> onUpdate { it.copy(disableCache = value) } },
                    )
                },
            ),
        )

        item(key = "dns_servers_title") {
            SmallTitle(
                text = stringResource(R.string.dns_servers),
                insideMargin = PaddingValues(start = 14.dp, top = 20.dp, end = 14.dp, bottom = 8.dp),
            )
        }
        groupedCardItems(
            keyPrefix = "dns_servers",
            items = document.servers.mapIndexed { index, server ->
                CardItem(index.toString()) {
                    if (editingServer == index && server.type in DnsServerDraft.supportedTypes) {
                        DnsServerEditor(
                            server = server,
                            onUpdate = { transform -> onUpdateServer(index, transform) },
                            onDone = { onEditingServerChange(-1) },
                            onDelete = { onRemoveServer(index) },
                        )
                    } else {
                        ArrowPreference(
                            title = server.tag.ifBlank { stringResource(R.string.dns_server_unnamed) },
                            summary = dnsServerSummary(server),
                            startAction = {
                                Icon(
                                    imageVector = Icons.Rounded.Dns,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            },
                            onClick = {
                                if (server.type in DnsServerDraft.supportedTypes) {
                                    onEditingServerChange(index)
                                } else {
                                    onOpenAdvanced()
                                }
                            },
                        )
                    }
                }
            } + CardItem("add") {
                ArrowPreference(
                    title = stringResource(R.string.dns_add_server),
                    startAction = {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    },
                    onClick = onAddServer,
                )
            },
        )

        groupedCardSection(
            keyPrefix = "dns_advanced",
            title = { stringResource(R.string.dns_advanced) },
            items = listOf(
                CardItem("rules") {
                    ArrowPreference(
                        title = stringResource(R.string.dns_rules),
                        summary = stringResource(R.string.dns_rules_summary, document.ruleCount),
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Code,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        },
                        onClick = onOpenAdvanced,
                    )
                },
                CardItem("json") {
                    ArrowPreference(
                        title = stringResource(R.string.dns_advanced_json),
                        summary = stringResource(R.string.dns_advanced_json_summary),
                        startAction = {
                            Icon(
                                imageVector = Icons.Rounded.Code,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 6.dp),
                            )
                        },
                        onClick = onOpenAdvanced,
                    )
                },
            ),
        )
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun DnsServerEditor(
    server: DnsServerDraft,
    onUpdate: ((DnsServerDraft) -> DnsServerDraft) -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    val types = DnsServerDraft.supportedTypes
    val typeLabels = listOf("DoH", "DoT", "DoQ", "UDP", "TCP", "Local", "Hosts", "Group")
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TextField(
            value = server.tag,
            onValueChange = { value -> onUpdate { it.copy(tag = value) } },
            label = stringResource(R.string.dns_server_tag),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            singleLine = true,
        )
        OverlayDropdownPreference(
            title = stringResource(R.string.dns_server_type),
            items = typeLabels,
            selectedIndex = types.indexOf(server.type).coerceAtLeast(0),
            onSelectedIndexChange = { index -> onUpdate { it.copy(type = types[index]) } },
        )
        when (server.type) {
            "group" -> MultilineField(
                value = server.groupServers,
                label = stringResource(R.string.dns_group_members),
                onValueChange = { value -> onUpdate { it.copy(groupServers = value) } },
            )
            "hosts" -> MultilineField(
                value = server.predefinedHosts,
                label = stringResource(R.string.dns_hosts),
                onValueChange = { value -> onUpdate { it.copy(predefinedHosts = value) } },
            )
            "local" -> Unit
            else -> {
                TextField(
                    value = server.server,
                    onValueChange = { value -> onUpdate { it.copy(server = value) } },
                    label = stringResource(R.string.dns_server_address),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    singleLine = true,
                )
                TextField(
                    value = server.serverPort,
                    onValueChange = { value ->
                        if (value.all(Char::isDigit)) onUpdate { it.copy(serverPort = value) }
                    },
                    label = stringResource(R.string.dns_server_port),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    singleLine = true,
                )
                if (server.type == "https") {
                    TextField(
                        value = server.path,
                        onValueChange = { value -> onUpdate { it.copy(path = value) } },
                        label = stringResource(R.string.dns_server_path),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        singleLine = true,
                    )
                }
                TextField(
                    value = server.domainResolver,
                    onValueChange = { value -> onUpdate { it.copy(domainResolver = value) } },
                    label = stringResource(R.string.dns_domain_resolver),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    singleLine = true,
                )
                TextField(
                    value = server.detour,
                    onValueChange = { value -> onUpdate { it.copy(detour = value) } },
                    label = stringResource(R.string.dns_detour),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    singleLine = true,
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.common_delete),
                modifier = Modifier.weight(1f),
                onClick = onDelete,
            )
            TextButton(
                text = stringResource(R.string.common_done),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onDone,
            )
        }
    }
}

@Composable
private fun MultilineField(value: String, label: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        minLines = 3,
        maxLines = 8,
    )
}

@Composable
private fun dnsServerSummary(server: DnsServerDraft): String = when (server.type) {
    "group" -> stringResource(R.string.dns_group_summary, server.groupServers.lines().count(String::isNotBlank))
    "hosts" -> stringResource(R.string.dns_hosts_summary, server.predefinedHosts.lines().count(String::isNotBlank))
    "local" -> "Local"
    else -> listOf(server.type.uppercase(), server.server).filter(String::isNotBlank).joinToString(" · ")
}

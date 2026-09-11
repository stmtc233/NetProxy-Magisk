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
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
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
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun LocalRulesScreen(
    initialKind: String,
    onBack: () -> Unit,
    viewModel: LocalRulesViewModel = netProxyViewModel(),
) {
    val navigator = LocalNavigator.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = rememberAppSnackbarHostState()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val barColor = if (backdrop != null) Color.Transparent else colorScheme.surface
    var selectedKind by remember(initialKind) { mutableStateOf(LocalRuleKind.fromRoute(initialKind)) }
    var editingRule by remember(selectedKind) { mutableIntStateOf(-1) }
    val kinds = LocalRuleKind.entries
    val tabs = listOf(
        stringResource(R.string.local_rules_proxy),
        stringResource(R.string.local_rules_direct),
        stringResource(R.string.local_rules_block),
    )

    LaunchedEffect(Unit) { viewModel.load() }
    SnackbarNoticeEffect(
        eventId = state.noticeId,
        message = if (state.saved) stringResource(R.string.local_rules_saved) else state.error,
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
                    title = stringResource(R.string.local_rules_settings),
                    navigationIcon = { BackIconButton(onClick = onBack) },
                    scrollBehavior = scrollBehavior,
                    actions = {
                        IconButton(
                            enabled = !state.loading && !state.saving && state.modified.isNotEmpty(),
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
                    bottomContent = {
                        TabRow(
                            tabs = tabs,
                            selectedTabIndex = kinds.indexOf(selectedKind),
                            onTabSelected = { selectedKind = kinds[it] },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            colors = TabRowDefaults.tabRowColors(backgroundColor = barColor),
                            height = 40.dp,
                        )
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxHeight()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            val document = state.files[selectedKind]?.document
            when {
                state.loading && document == null -> Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) { InfiniteProgressIndicator() }

                document == null -> Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyCatalog(
                        text = stringResource(R.string.local_rules_load_failed),
                        onRefresh = viewModel::load,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }

                else -> LocalRulesContent(
                    kind = selectedKind,
                    document = document,
                    editingRule = editingRule,
                    onEditingRuleChange = { editingRule = it },
                    onUpdateRule = { index, transform ->
                        viewModel.updateRule(selectedKind, index, transform)
                    },
                    onAddRule = {
                        viewModel.addRule(selectedKind)
                        editingRule = document.rules.size
                    },
                    onRemoveRule = { index ->
                        viewModel.removeRule(selectedKind, index)
                        editingRule = -1
                    },
                    onOpenAdvanced = { navigator.push(Route.JsonEdit(selectedKind.documentId)) },
                    modifier = Modifier.padding(innerPadding),
                    nestedScrollConnection = scrollBehavior.nestedScrollConnection,
                )
            }
        }
    }
}

@Composable
private fun LocalRulesContent(
    kind: LocalRuleKind,
    document: LocalRulesDocument,
    editingRule: Int,
    onEditingRuleChange: (Int) -> Unit,
    onUpdateRule: (Int, (LocalRuleDraft) -> LocalRuleDraft) -> Unit,
    onAddRule: () -> Unit,
    onRemoveRule: (Int) -> Unit,
    onOpenAdvanced: () -> Unit,
    modifier: Modifier,
    nestedScrollConnection: NestedScrollConnection,
) {
    LazyColumn(
        modifier = modifier.fillMaxHeight()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(nestedScrollConnection)
            .padding(horizontal = 12.dp),
        overscrollEffect = null,
    ) {
        groupedCardItems(
            keyPrefix = "local_rules_${kind.name}",
            outerTopPadding = 16.dp,
            items = document.rules.mapIndexed { index, rule ->
                CardItem(index.toString()) {
                    if (editingRule == index) {
                        LocalRuleEditor(
                            rule = rule,
                            onUpdate = { transform -> onUpdateRule(index, transform) },
                            onDone = { onEditingRuleChange(-1) },
                            onDelete = { onRemoveRule(index) },
                        )
                    } else {
                        ArrowPreference(
                            title = stringResource(R.string.local_rule_name, index + 1),
                            summary = if (rule.conditionCount > 0) {
                                stringResource(R.string.local_rule_summary, rule.conditionCount)
                            } else {
                                stringResource(R.string.local_rule_advanced_summary)
                            },
                            startAction = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.AltRoute,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                )
                            },
                            onClick = { onEditingRuleChange(index) },
                        )
                    }
                }
            } + CardItem("add") {
                ArrowPreference(
                    title = stringResource(R.string.local_rule_add),
                    startAction = {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 6.dp),
                        )
                    },
                    onClick = onAddRule,
                )
            },
        )

        groupedCardSection(
            keyPrefix = "local_rules_advanced_${kind.name}",
            title = { stringResource(R.string.local_rules_advanced) },
            items = listOf(
                CardItem("json") {
                    ArrowPreference(
                        title = stringResource(R.string.local_rules_advanced_json),
                        summary = stringResource(R.string.local_rules_advanced_json_summary),
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
private fun LocalRuleEditor(
    rule: LocalRuleDraft,
    onUpdate: ((LocalRuleDraft) -> LocalRuleDraft) -> Unit,
    onDone: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RuleField(rule.domain, R.string.local_rule_domain) { value ->
            onUpdate { it.copy(domain = value) }
        }
        RuleField(rule.domainSuffix, R.string.local_rule_domain_suffix) { value ->
            onUpdate { it.copy(domainSuffix = value) }
        }
        RuleField(rule.domainKeyword, R.string.local_rule_domain_keyword) { value ->
            onUpdate { it.copy(domainKeyword = value) }
        }
        RuleField(rule.domainRegex, R.string.local_rule_domain_regex) { value ->
            onUpdate { it.copy(domainRegex = value) }
        }
        RuleField(rule.ipCidr, R.string.local_rule_ip_cidr) { value ->
            onUpdate { it.copy(ipCidr = value) }
        }
        RuleField(rule.port, R.string.local_rule_port) { value ->
            onUpdate { it.copy(port = value) }
        }
        RuleField(rule.portRange, R.string.local_rule_port_range) { value ->
            onUpdate { it.copy(portRange = value) }
        }
        RuleField(rule.network, R.string.local_rule_network) { value ->
            onUpdate { it.copy(network = value) }
        }
        RuleField(rule.protocol, R.string.local_rule_protocol) { value ->
            onUpdate { it.copy(protocol = value) }
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
private fun RuleField(value: String, label: Int, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(label),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        minLines = 2,
        maxLines = 6,
    )
}

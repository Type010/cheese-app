package com.cheese.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cheese.app.R
import com.cheese.app.domain.formatHex
import com.cheese.app.domain.parseHexOrNull
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** ROOT_CMD, KERNEL_BASE and the optional cheese debug knobs. */
@Composable
internal fun AdvancedScreen(
    state: CheeseUiState,
    actions: CheeseActions,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.advanced_settings),
                navigationIcon = {
                    IconButton(onClick = { actions.onCloseScreen(CheeseScreen.Advanced) }) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "manager") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.label_ksu_manager),
                        summary = state.runConfig.manager.label,
                        onClick = actions.onOpenManagerPicker,
                    )
                }
            }
            item(key = "root-cmd") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = state.runConfig.customRootCmd,
                            onValueChange = actions.onRootCmdChanged,
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.root_cmd_custom),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.root_cmd_custom_hint),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            item(key = "cheese-options") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        TextField(
                            value = state.runConfig.attempt,
                            onValueChange = { value ->
                                actions.onUpdateRunConfig { it.copy(attempt = value) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.label_attempt),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.label_attempt_summary),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(14.dp))
                        TextField(
                            value = state.runConfig.phyAddr,
                            onValueChange = { value ->
                                actions.onUpdateRunConfig { it.copy(phyAddr = value) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.label_phyaddr),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.label_phyaddr_summary),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Spacer(Modifier.height(14.dp))
                        TextField(
                            value = state.runConfig.logPath,
                            onValueChange = { value ->
                                actions.onUpdateRunConfig { it.copy(logPath = value) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.label_log_path),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.label_log_path_summary),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    SwitchPreference(
                        checked = state.runConfig.noRetry,
                        onCheckedChange = { checked ->
                            actions.onUpdateRunConfig { it.copy(noRetry = checked) }
                        },
                        title = stringResource(R.string.label_no_retry),
                        summary = stringResource(R.string.label_no_retry_summary),
                    )
                }
            }
            item(key = "about") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.about_action),
                        summary = stringResource(R.string.about_action_summary),
                        onClick = actions.onOpenAbout,
                    )
                }
            }
            item(key = "bottom") {
                Spacer(Modifier.height(24.dp).navigationBarsPadding())
            }
        }
    }
}

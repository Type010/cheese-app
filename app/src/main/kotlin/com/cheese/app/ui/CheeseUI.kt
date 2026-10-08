package com.cheese.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cheese.app.R
import com.cheese.app.domain.ConfigSource
import com.cheese.app.domain.LogLine
import com.cheese.app.domain.jsonText
import com.cheese.app.domain.LogTone
import com.cheese.app.domain.KsuManager
import com.cheese.app.domain.LocalImageKind
import com.cheese.app.shizuku.ShizukuStatus
import com.cheese.app.domain.RunConfig
import com.cheese.app.domain.formatHex
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.isDynamicColor
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** Callbacks raised by the UI; every decision stays in the view model. */
data class CheeseActions(
    val onOpenLoadConfig: () -> Unit,
    val onOpenAdvanced: () -> Unit,
    val onOpenAbout: () -> Unit,
    val onOpenGitHub: () -> Unit,
    val onCloseScreen: (CheeseScreen) -> Unit,
    val onImportOffsetsJson: () -> Unit,
    val onOpenCloudConfigs: () -> Unit,
    val onDownloadCloudConfig: (String) -> Unit,
    val onParseOta: () -> Unit,
    val onParseLocal: () -> Unit,
    val onPickLocalImage: (LocalImageKind) -> Unit,
    val onDismissLocalImage: () -> Unit,
    val onUrlChanged: (String) -> Unit,
    val onConfirmUrl: () -> Unit,
    val onDismissUrl: () -> Unit,
    val onLoadConfig: (String) -> Unit,
    val onPreviewConfig: (String) -> Unit,
    val onDismissPreview: () -> Unit,
    val onShareConfig: (String) -> Unit,
    val onRequestDeleteConfig: (String) -> Unit,
    val onDeleteConfig: (String) -> Unit,
    val onDismissDeleteConfig: () -> Unit,
    val onUpdateRunConfig: ((RunConfig) -> RunConfig) -> Unit,
    val onOpenManagerPicker: () -> Unit,
    val onPickManager: (KsuManager) -> Unit,
    val onDismissManagerPicker: () -> Unit,
    val onRun: () -> Unit,
    val onCancelRun: () -> Unit,
    val onShowExecutionSheet: () -> Unit,
    val onRootCmdChanged: (String) -> Unit,
    val onCloseExecutionSheet: () -> Unit,
    val onClearLog: () -> Unit,
    val onNoticeShown: () -> Unit,
)

@Composable
internal fun CheeseApp(state: CheeseUiState, actions: CheeseActions) {
    MiuixTheme(
        colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
    ) {
        val snackbarHostState = remember { SnackbarHostState() }
        val clipboard = LocalClipboardManager.current
        val scope = rememberCoroutineScope()
        val copy: (String, String) -> Unit = { text, label ->
            clipboard.setText(AnnotatedString(text))
            scope.launch { snackbarHostState.showSnackbar(label) }
        }

        // One-off messages that have no other surface, e.g. why a saved
        // configuration was refused when the user tried to load it.
        LaunchedEffect(state.notice) {
            state.notice?.let { message ->
                snackbarHostState.showSnackbar(message)
                actions.onNoticeShown()
            }
        }

        val desiredPath = navigationPath(state)
        val backStack = remember {
            navBackStackOf(CheeseScreen.Main).apply { addAll(desiredPath.drop(1)) }
        }
        LaunchedEffect(desiredPath) { syncNavigationPath(backStack, desiredPath) }
        val swipeBack = if (LocalLayoutDirection.current == LayoutDirection.Rtl) {
            NavSwipeDirection.RightToLeft
        } else {
            NavSwipeDirection.LeftToRight
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = {
                SnackbarHost(state = snackbarHostState, modifier = Modifier.padding(bottom = 20.dp))
            },
        ) { _ ->
            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.fillMaxSize(),
                    effects = NavDisplayEffects(cornerClipRadius = rememberNavSystemCornerRadius()),
                    onBack = {
                        when {
                            state.executionSheetVisible -> actions.onCloseExecutionSheet()
                            state.localImageDialogVisible -> actions.onDismissLocalImage()
                            state.urlDialogVisible -> actions.onDismissUrl()
                            state.previewConfig != null -> actions.onDismissPreview()
                            else -> {
                                val screen = backStack.lastOrNull() as? CheeseScreen
                                if (screen != null && screen != CheeseScreen.Main) {
                                    actions.onCloseScreen(screen)
                                    backStack.removeAt(backStack.lastIndex)
                                }
                            }
                        }
                    },
                ) {
                    entry<CheeseScreen.Main>(swipeDismiss = NavSwipeDirection.None) {
                        MainScreen(state = state, actions = actions, copy = copy)
                    }
                    entry<CheeseScreen.LoadConfig>(swipeDismiss = swipeBack) {
                        LoadConfigScreen(state = state, actions = actions, copy = copy)
                    }
                    entry<CheeseScreen.Cloud>(swipeDismiss = swipeBack) {
                        CloudConfigScreen(state = state, actions = actions)
                    }
                    entry<CheeseScreen.Advanced>(swipeDismiss = swipeBack) {
                        AdvancedScreen(state = state, actions = actions)
                    }
                    entry<CheeseScreen.About>(swipeDismiss = swipeBack) {
                        AboutScreen(
                            onBack = { actions.onCloseScreen(CheeseScreen.About) },
                            onOpenGitHub = actions.onOpenGitHub,
                        )
                    }
                }
                LocalImageDialog(state = state, actions = actions)
                ManagerPickerDialog(state = state, actions = actions)
                UrlDialog(state = state, actions = actions)
                ConfigPreviewDialog(state = state, actions = actions)
                DeleteConfigDialog(state = state, actions = actions)
                ExecutionSheet(state = state, actions = actions, onCopyLog = copy)
            }
        }
    }
}

internal fun navigationPath(state: CheeseUiState): List<CheeseScreen> {
    val path = mutableListOf<CheeseScreen>(CheeseScreen.Main)
    when (state.screen) {
        CheeseScreen.Main -> Unit
        is CheeseScreen.LoadConfig -> path += CheeseScreen.LoadConfig
        is CheeseScreen.Cloud -> {
            // Opened from load-config, so keep it underneath for the back swipe.
            path += CheeseScreen.LoadConfig
            path += CheeseScreen.Cloud
        }

        is CheeseScreen.Advanced -> path += CheeseScreen.Advanced
        is CheeseScreen.About -> {
            // Keep the parent in the stack so back returns there, with animation.
            if (state.aboutParent == CheeseScreen.Advanced) path += CheeseScreen.Advanced
            path += CheeseScreen.About
        }
    }
    return path
}

internal fun syncNavigationPath(backStack: NavBackStack, desired: List<CheeseScreen>) {
    var common = 0
    while (common < backStack.size && common < desired.size && backStack[common] == desired[common]) {
        common++
    }
    while (backStack.size > common) backStack.removeAt(backStack.lastIndex)
    backStack.addAll(desired.drop(common))
}

@Composable
private fun MainScreen(
    state: CheeseUiState,
    actions: CheeseActions,
    copy: (String, String) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_name),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .fillMaxHeight()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .imePadding(),
            contentPadding = pageContentPadding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "status") {
                ConfigStatusCard(
                    state = state,
                    onClick = actions.onOpenLoadConfig,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "device") {
                DeviceInfoCard(state = state, modifier = Modifier.fillMaxWidth())
            }
            item(key = "entries") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    ArrowPreference(
                        title = stringResource(R.string.advanced_settings),
                        summary = stringResource(R.string.advanced_settings_summary),
                        onClick = actions.onOpenAdvanced,
                    )
                }
            }
            item(key = "run") {
                TextButton(
                    text = stringResource(
                        if (state.running) R.string.action_running else R.string.action_run,
                    ),
                    // While a run is in progress the button reopens the log
                    // sheet instead of starting a second run.
                    enabled = if (state.running) {
                        true
                    } else {
                        state.offsets != null &&
                            state.shizukuStatus == ShizukuStatus.Ready &&
                            !state.busy
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = if (state.running) actions.onShowExecutionSheet else actions.onRun,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Big status card, laid out like the upstream activation card. */
@Composable
private fun ConfigStatusCard(
    state: CheeseUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Green means this device extracted the offsets from a boot image and checked
    // the kernel version itself. Everything else — imported, downloaded, or a
    // config written before the `source` key existed — reads as amber.
    val source = state.offsets?.source
    val ready = state.offsetsReady && source == ConfigSource.Boot
    val imported = state.offsetsReady && source != ConfigSource.Boot
    val busy = state.busy || state.running
    val icon = when {
        busy -> Icons.Rounded.RemoveCircleOutline
        ready -> Icons.Rounded.CheckCircleOutline
        imported -> Icons.Rounded.Info
        else -> Icons.Rounded.ErrorOutline
    }
    val backgroundColor = when {
        ready && !busy -> when {
            isDynamicColor -> MiuixTheme.colorScheme.secondaryContainer
            isSystemInDarkTheme() -> Color(0xFF1A3825)
            else -> Color(0xFFDFFAE4)
        }

        busy -> when {
            isDynamicColor -> MiuixTheme.colorScheme.tertiaryContainer
            isSystemInDarkTheme() -> Color(0xFF3E2F1B)
            else -> Color(0xFFFFF0DB)
        }

        imported -> when {
            isDynamicColor -> MiuixTheme.colorScheme.tertiaryContainer
            isSystemInDarkTheme() -> Color(0xFF3A3418)
            else -> Color(0xFFFFF6D6)
        }

        else -> MiuixTheme.colorScheme.errorContainer
    }
    val tint = if (isDynamicColor) {
        when (icon) {
            Icons.Rounded.CheckCircleOutline -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
            Icons.Rounded.RemoveCircleOutline, Icons.Rounded.Info ->
                MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)

            else -> MiuixTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
        }
    } else {
        when (icon) {
            Icons.Rounded.CheckCircleOutline -> Color(0xFF36D167)
            Icons.Rounded.RemoveCircleOutline, Icons.Rounded.Info -> Color(0xFFF5A623)
            else -> Color(0xFFF72727)
        }
    }
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = backgroundColor),
        onClick = onClick,
        showIndication = true,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
                    .size(110.dp),
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 120.dp, bottom = 14.dp),
            ) {
                Text(
                    text = stringResource(
                        when {
                            busy -> R.string.status_busy
                            ready -> R.string.status_ready
                            imported -> R.string.status_imported
                            else -> R.string.status_missing
                        },
                    ),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                // The imported title says all there is to say, so it carries no
                // summary line.
                val summary = when {
                    busy -> stringResource(R.string.status_busy_summary)
                    ready -> stringResource(R.string.status_ready_summary)
                    imported -> null
                    else -> stringResource(R.string.status_missing_summary)
                }
                if (summary != null) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = summary,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoCard(state: CheeseUiState, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(16.dp),
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                DeviceInfoItem(title = stringResource(R.string.label_model), value = state.deviceName)
                DeviceInfoItem(title = stringResource(R.string.label_soc), value = state.socName)
                DeviceInfoItem(title = stringResource(R.string.label_kernel), value = state.kernelRelease)
                DeviceInfoItem(
                    title = stringResource(R.string.label_ksu_manager),
                    // Follows the selection in the run settings rather than whatever is
                    // installed; the install state is spelled out so a run that
                    // would end in NO_LIBKSUD is obvious beforehand.
                    value = managerLabel(state),
                )
                DeviceInfoItem(
                    title = stringResource(R.string.shizuku_card_title),
                    value = state.shizukuStatus.label,
                )
            }
        }
    }
}

/** `KernelSU (installed)`: the selected manager plus whether it is installed. */
@Composable
private fun managerLabel(state: CheeseUiState): String {
    val manager = state.runConfig.manager
    return manager.label + stringResource(
        if (manager.packageName in state.installedManagers) {
            R.string.label_ksu_installed
        } else {
            R.string.label_ksu_missing
        },
    )
}

@Composable
private fun DeviceInfoItem(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = value.ifBlank { "—" },
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.68f),
        )
    }
}

@Composable
private fun LocalImageDialog(state: CheeseUiState, actions: CheeseActions) {
    OverlayDialog(
        show = state.localImageDialogVisible,
        title = stringResource(R.string.local_image_title),
        onDismissRequest = actions.onDismissLocalImage,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for (kind in LocalImageKind.entries) {
                Button(
                    onClick = { actions.onPickLocalImage(kind) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.secondaryVariant,
                        contentColor = MiuixTheme.colorScheme.onSecondaryVariant,
                    ),
                ) {
                    Text(
                        text = kind.label,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = actions.onDismissLocalImage,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Which manager's libksud.so the built-in ROOT_CMD should late-load. */
@Composable
private fun ManagerPickerDialog(state: CheeseUiState, actions: CheeseActions) {
    OverlayDialog(
        show = state.managerDialogVisible,
        title = stringResource(R.string.manager_picker_title),
        onDismissRequest = actions.onDismissManagerPicker,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            for (manager in KsuManager.entries) {
                Button(
                    onClick = { actions.onPickManager(manager) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        color = MiuixTheme.colorScheme.secondaryVariant,
                        contentColor = MiuixTheme.colorScheme.onSecondaryVariant,
                    ),
                ) {
                    Text(
                        text = manager.label,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(10.dp))
            }
            TextButton(
                text = stringResource(R.string.action_cancel),
                onClick = actions.onDismissManagerPicker,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun UrlDialog(state: CheeseUiState, actions: CheeseActions) {    OverlayDialog(
        show = state.urlDialogVisible,
        title = stringResource(R.string.parse_ota),
        onDismissRequest = actions.onDismissUrl,
    ) {
        Column {
            Text(
                text = stringResource(R.string.parse_ota_hint),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(12.dp))
            TextField(
                value = state.urlInput,
                onValueChange = actions.onUrlChanged,
                label = stringResource(R.string.parse_ota_url_label),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (state.urlError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.parse_ota_url_invalid),
                    color = Color(0xFFE53935),
                    style = MiuixTheme.textStyles.footnote1,
                )
            }
            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = actions.onDismissUrl,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.action_start),
                    onClick = actions.onConfirmUrl,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Preview of one saved configuration, with the option to load it. */
@Composable
private fun ConfigPreviewDialog(
    state: CheeseUiState,
    actions: CheeseActions,
) {
    val config = state.previewConfig ?: return
    OverlayDialog(
        show = true,
        title = stringResource(R.string.config_preview),
        summary = config.name,
        onDismissRequest = actions.onDismissPreview,
    ) {
        Column {
            Text(
                text = stringResource(R.string.config_preview_raw),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(6.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(Color(0xFF0B1220), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            // Configurations saved before the raw text was stored are
                            // rendered from their fields instead.
                            text = config.jsonText(
                                device = state.deviceName,
                                buildId = state.buildId,
                                deviceRelease = state.kernelRelease,
                            ),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = Color(0xFFD1D5DB),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(
                    text = stringResource(R.string.action_close),
                    onClick = actions.onDismissPreview,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.action_use_config),
                    onClick = {
                        actions.onLoadConfig(config.name)
                        actions.onDismissPreview()
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                TextButton(
                    text = stringResource(R.string.action_share),
                    onClick = { actions.onShareConfig(config.name) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.action_delete),
                    onClick = { actions.onRequestDeleteConfig(config.name) },
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Confirmation for deleting one saved configuration. */
@Composable
private fun DeleteConfigDialog(state: CheeseUiState, actions: CheeseActions) {
    val name = state.pendingDelete ?: return
    OverlayDialog(
        show = true,
        title = stringResource(R.string.delete_config_title),
        summary = name,
        onDismissRequest = actions.onDismissDeleteConfig,
    ) {
        Column {
            Text(
                text = stringResource(R.string.delete_config_message),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(20.dp))
            Row {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = actions.onDismissDeleteConfig,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.action_delete),
                    onClick = { actions.onDeleteConfig(name) },
                    colors = ButtonDefaults.textButtonColors(
                        color = MiuixTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}


@Composable
private fun ExecutionSheet(
    state: CheeseUiState,
    actions: CheeseActions,
    onCopyLog: (String, String) -> Unit,
) {
    val copyLabel = stringResource(R.string.action_copy_log)
    val closeLabel = stringResource(R.string.action_close)
    // The overlay renders this content in its own window and does not re-run the
    // captured lambdas when the screen recomposes, so anything that changes while
    // the sheet is open is read through a State: reading it inside those lambdas
    // makes their own scope observe the change. Without this the panel kept
    // showing the log as it looked when the sheet opened, and only closing and
    // reopening it revealed the rest.
    val logLines by rememberUpdatedState(state.logLines)
    val running by rememberUpdatedState(state.running)
    OverlayBottomSheet(
        show = state.executionSheetVisible,
        title = stringResource(R.string.log_title),
        allowDismiss = true,
        onDismissRequest = actions.onCloseExecutionSheet,
        startAction = {
            IconButton(
                onClick = { onCopyLog(logLines.joinToString("\n") { it.text }, copyLabel) },
            ) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = stringResource(R.string.action_copy),
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
        endAction = {
            IconButton(onClick = actions.onCloseExecutionSheet) {
                Icon(imageVector = MiuixIcons.Close, contentDescription = closeLabel)
            }
        },
        content = {
            Column {
                LogPanel(
                    lines = logLines,
                    modifier = Modifier
                        .fillMaxWidth()
                        // A fixed height, not heightIn: the sheet sizes itself from
                        // the content it sees when it opens, so a panel that grows
                        // with the line count was measured while still empty and
                        // stayed blank until the sheet was rebuilt.
                        .height(420.dp),
                )
                if (running) {
                    Spacer(Modifier.height(12.dp))
                    TextButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = actions.onCancelRun,
                        colors = ButtonDefaults.textButtonColors(
                            color = MiuixTheme.colorScheme.error,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(16.dp).navigationBarsPadding())
            }
        },
    )
}

@Composable
internal fun LogPanel(lines: List<LogLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    // One scrollable box, sized by the caller's heightIn. It used to be a Column
    // holding a SelectionContainer holding a weighted LazyColumn -- the weight
    // belongs to the Column scope but the list's real parent was the
    // SelectionContainer, so it never applied and the box stayed collapsed until
    // the sheet was rebuilt. Copying has its own button, so per-line selection
    // is not worth that.
    LazyColumn(
        state = listState,
        modifier = modifier
            .background(Color(0xFF0B1220), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        items(lines) { line ->
            Text(
                text = line.text.trimEnd('\r', '\n'),
                color = line.tone.color(),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

private fun LogTone.color(): Color = when (this) {
    LogTone.Failure -> Color(0xFFFF6B6B)
    LogTone.Success -> Color(0xFF5FD68A)
    LogTone.Stage -> Color(0xFFFFC94D)
    LogTone.Hint -> Color(0xFF60A5FA)
    LogTone.Normal -> Color(0xFFD1D5DB)
}

/** Same layout rule as upstream: an 800dp column, centred on wide screens. */
@Composable
internal fun pageContentPadding(
    scaffoldPadding: PaddingValues,
    top: Dp = 8.dp,
    bottom: Dp = 12.dp,
    horizontalMin: Dp = 12.dp,
): PaddingValues {
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val horizontal = ((windowWidth - 800.dp) / 2).coerceAtLeast(horizontalMin)
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = scaffoldPadding.calculateTopPadding() + top,
        bottom = scaffoldPadding.calculateBottomPadding() + bottom,
    )
}

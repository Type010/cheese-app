package com.cheese.app.ui

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cheese.app.data.CheeseRepository
import com.cheese.app.data.ConfigStore
import com.cheese.app.data.cloud.CloudEntry
import com.cheese.app.domain.CheeseOffsets
import com.cheese.app.domain.ConfigSource
import com.cheese.app.domain.KsuOutcome
import com.cheese.app.domain.rejectionReason
import com.cheese.app.domain.KsuManager
import com.cheese.app.domain.LocalImageKind
import com.cheese.app.domain.LogLine
import com.cheese.app.domain.ParseOutcome
import com.cheese.app.domain.RunConfig
import com.cheese.app.domain.SavedConfig
import com.cheese.app.domain.publishJson
import com.cheese.app.domain.classifyLogLine
import com.cheese.app.domain.buildConfigJson
import com.cheese.app.domain.configFileName
import com.cheese.app.domain.decideKsuOutcome
import com.cheese.app.domain.formatHex
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import com.cheese.app.shizuku.ShizukuStatus
import top.yukonga.miuix.kmp.nav.core.NavKey

/** Screens; a sealed interface so miuix-nav can drive a back stack. */
sealed interface CheeseScreen : NavKey {
    data object Main : CheeseScreen
    data object LoadConfig : CheeseScreen
    data object Cloud : CheeseScreen
    data object Advanced : CheeseScreen
    data object About : CheeseScreen
}

data class CheeseUiState(
    val screen: CheeseScreen = CheeseScreen.Main,
    val offsets: CheeseOffsets? = null,
    val configs: List<SavedConfig> = emptyList(),
    val activeConfig: String? = null,
    val runConfig: RunConfig = RunConfig(),
    val deviceName: String = "",
    val socName: String = "",
    val kernelRelease: String = "",
    val buildId: String = "",
    /** Packages of the privilege managers that are actually installed. */
    val installedManagers: Set<String> = emptySet(),
    val logLines: List<LogLine> = emptyList(),
    val executionSheetVisible: Boolean = false,
    val busy: Boolean = false,
    val running: Boolean = false,
    val lastMessage: String? = null,
    val localImageDialogVisible: Boolean = false,
    val managerDialogVisible: Boolean = false,
    val urlDialogVisible: Boolean = false,
    val urlInput: String = "",
    val urlError: Boolean = false,
    val previewConfig: SavedConfig? = null,
    /** Name waiting for delete confirmation. */
    val pendingDelete: String? = null,
    /** Configurations published in the cloud bucket. */
    val cloudEntries: List<CloudEntry> = emptyList(),
    val cloudLoading: Boolean = false,
    val cloudError: String? = null,
    /** Config the UI should hand to the system share sheet right now. */
    val shareRequest: String? = null,
    /** One-off message for the snackbar, e.g. why a configuration was refused. */
    val notice: String? = null,
    val ksuOutcome: KsuOutcome? = null,
    val shizukuStatus: ShizukuStatus = ShizukuStatus.NotRunning,
    /** Screen About was opened from, so back returns there instead of Home. */
    val aboutParent: CheeseScreen = CheeseScreen.Main,
) {
    /** Offsets are loaded and the structure check passed. */
    val offsetsReady: Boolean get() = offsets != null && offsets.verified
}

class CheeseViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = (application as com.cheese.app.CheeseApplication).repository
    private val store = ConfigStore(application)

    /** What is actually loaded at startup; null when the active one is unusable. */
    private val initialOffsets = store.activeOffsets()

    private val _state = MutableStateFlow(
        CheeseUiState(
            offsets = initialOffsets,
            configs = store.loadConfigs(),
            // The "loaded" badge follows what really loaded: an active config this
            // device cannot use is not loaded, and must not be shown as such.
            activeConfig = store.activeName()?.takeIf { initialOffsets != null },
            runConfig = store.loadRunConfig(),
            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            socName = Build.SOC_MODEL.orEmpty(),
            kernelRelease = System.getProperty("os.version").orEmpty(),
            buildId = Build.DISPLAY.orEmpty(),
            installedManagers = detectInstalledManagers(application),
        ),
    )
    val state: StateFlow<CheeseUiState> = _state.asStateFlow()

    init {
        // Binder events change availability while the app is open.
        repository.onShizukuStatusChanged {
            _state.update { it.copy(shizukuStatus = repository.shizukuStatus) }
        }
        // The sticky binder listener can fire before this ViewModel exists, so
        // read the current status once here as well.
        refreshShizukuStatus()
    }

    /** Kind chosen in the local-image dialog; the activity reads it when picking. */
    var pendingKind: LocalImageKind? = null
        private set

    /** The in-flight run, so [cancelRun] can stop it. */
    private var runJob: Job? = null

    // ---- navigation --------------------------------------------------------

    fun openLoadConfig() = _state.update { it.copy(screen = CheeseScreen.LoadConfig) }

    fun openAdvanced() = _state.update { it.copy(screen = CheeseScreen.Advanced) }

    fun openAbout() = _state.update {
        it.copy(screen = CheeseScreen.About, aboutParent = it.screen)
    }
    fun closeScreen(screen: CheeseScreen) = _state.update { state ->
        if (state.screen != screen) {
            state
        } else when (screen) {
            // About returns to whatever opened it (Advanced), not to Home.
            is CheeseScreen.About -> state.copy(screen = state.aboutParent)
            // Cloud is opened from the load-config screen.
            is CheeseScreen.Cloud -> state.copy(screen = CheeseScreen.LoadConfig)
            else -> state.copy(screen = CheeseScreen.Main)
        }
    }

    // ---- log ---------------------------------------------------------------

    fun setExecutionSheetVisible(visible: Boolean) =
        _state.update { it.copy(executionSheetVisible = visible) }

    fun clearLog() = _state.update { it.copy(logLines = emptyList()) }

    fun clearMessage() = _state.update { it.copy(lastMessage = null) }

    /** The snackbar has shown [CheeseUiState.notice]. */
    fun clearNotice() = _state.update { it.copy(notice = null) }

    private fun appendLog(text: String) = _state.update { state ->
        state.copy(
            logLines = (state.logLines + LogLine(text, classifyLogLine(text))).takeLast(MAX_LOG_LINES),
        )
    }

    // ---- parsing -----------------------------------------------------------

    fun openLocalImageDialog() = _state.update { it.copy(localImageDialogVisible = true) }

    fun dismissLocalImageDialog() = _state.update { it.copy(localImageDialogVisible = false) }

    fun chooseLocalImageKind(kind: LocalImageKind) {
        pendingKind = kind
        _state.update { it.copy(localImageDialogVisible = false) }
    }

    fun parseImage(uri: Uri, kind: LocalImageKind? = null) {
        if (_state.value.busy || _state.value.running) return
        startOperation(kind?.let { "正在解析 ${it.label}" } ?: "正在解析镜像")
        viewModelScope.launch {
            // A boot image, a payload and a full OTA package all end up as the
            // boot partition, so every local parse is the same source.
            finish(
                repository.parseImage(uri, ConfigSource.Boot) { appendLog(it) },
                explicitName = null,
            )
        }
    }

    fun openUrlDialog() = _state.update {
        it.copy(urlDialogVisible = true, urlInput = "", urlError = false)
    }

    fun dismissUrlDialog() = _state.update { it.copy(urlDialogVisible = false, urlError = false) }

    fun onUrlChanged(value: String) = _state.update { it.copy(urlInput = value, urlError = false) }

    fun confirmUrl() {
        val url = _state.value.urlInput.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            _state.update { it.copy(urlError = true) }
            return
        }
        _state.update { it.copy(urlDialogVisible = false, urlError = false) }
        if (_state.value.busy || _state.value.running) return
        startOperation("正在下载全量包")
        viewModelScope.launch {
            finish(repository.parseUrl(url) { appendLog(it) }, explicitName = null)
        }
    }

    fun importOffsetsJson(uri: Uri) {
        if (_state.value.busy || _state.value.running) return
        startOperation("正在导入 offsets.json")
        viewModelScope.launch {
            finish(
                repository.importOffsetsJson(uri) { appendLog(it) },
                explicitName = uri.lastPathSegment
                    ?.substringAfterLast('/')
                    ?.takeIf { it.endsWith(".json", ignoreCase = true) },
            )
        }
    }

    // ---- cloud configurations ---------------------------------------------

    fun openCloudConfigs() {
        _state.update { it.copy(screen = CheeseScreen.Cloud) }
        refreshCloudConfigs()
    }

    fun refreshCloudConfigs() {
        if (_state.value.cloudLoading) return
        _state.update { it.copy(cloudLoading = true, cloudError = null) }
        viewModelScope.launch {
            val result = runCatching { repository.listCloudConfigs() }
            _state.update {
                it.copy(
                    cloudLoading = false,
                    cloudEntries = result.getOrDefault(emptyList()),
                    cloudError = result.exceptionOrNull()?.let { error -> "加载失败：${error.message}" },
                )
            }
        }
    }

    fun downloadCloudConfig(key: String) {
        if (_state.value.busy || _state.value.running) return
        startOperation("正在下载云端配置")
        viewModelScope.launch {
            finish(repository.downloadCloudConfig(key) { appendLog(it) }, explicitName = key)
        }
    }

    private fun startOperation(message: String) = _state.update {
        it.copy(
            screen = CheeseScreen.LoadConfig,
            busy = true,
            lastMessage = message,
            executionSheetVisible = true,
            logLines = emptyList(),
        )
    }

    private suspend fun finish(outcome: ParseOutcome, explicitName: String?) {
        _state.update { it.copy(busy = false) }
        when (outcome) {
            is ParseOutcome.Success -> {
                val message = storeParsed(outcome.offsets, explicitName, outcome.raw)
                _state.update {
                    it.copy(
                        offsets = outcome.offsets,
                        configs = store.loadConfigs(),
                        activeConfig = store.activeName(),
                        executionSheetVisible = false,
                        lastMessage = message,
                    )
                }
            }

            is ParseOutcome.Failure -> {
                appendLog("error: ${outcome.message}")
                _state.update { it.copy(lastMessage = outcome.message) }
            }
        }
    }

    /** Store the result; an identical configuration is not stored twice. */
    private fun storeParsed(offsets: CheeseOffsets, explicitName: String?, raw: String?): String {
        val state = _state.value
        val name = explicitName
            ?: configFileName(state.deviceName.ifBlank { "device" }, state.buildId)
        val config = SavedConfig(
            name = name,
            initTaskOff = offsets.initTaskOff,
            swapperPgDirOff = offsets.swapperPgDirOff,
            kernelBase = offsets.kernelBase,
            release = offsets.imageRelease,
            verified = offsets.verified,
            source = offsets.source,
            savedAtMillis = offsets.savedAtMillis,
            raw = raw ?: buildConfigJson(
                name = name,
                device = state.deviceName,
                buildId = state.buildId,
                // Always the image's own release: a config is only ever written
                // for an image that reported one and matches this kernel.
                kernelRelease = offsets.imageRelease.orEmpty(),
                offsets = offsets,
            ),
        )
        val changed = store.saveConfig(config)
        store.setActive(name)
        return if (changed) "已保存配置 $name" else "配置已存在，未重复保存"
    }

    // ---- saved configurations ---------------------------------------------

    fun loadConfig(name: String) {
        val config = store.loadConfigs().firstOrNull { it.name == name } ?: return
        val offsets = config.toOffsets()
        // Only a configuration this device may actually use can be activated.
        // Anything else is refused with the reason instead of loaded silently.
        val reason = offsets.rejectionReason()
        if (reason != null) {
            _state.update { it.copy(notice = "$name 不可用：$reason") }
            return
        }
        store.setActive(name)
        _state.update {
            it.copy(offsets = offsets, activeConfig = name, lastMessage = "已加载 $name")
        }
    }

    /** Open the preview dialog of one saved configuration. */
    fun previewConfig(name: String) = _state.update { state ->
        state.copy(previewConfig = state.configs.firstOrNull { it.name == name })
    }

    fun dismissPreview() = _state.update { it.copy(previewConfig = null) }

    /** Ask before deleting: the entry cannot be recovered from inside the app. */
    fun requestDeleteConfig(name: String) = _state.update {
        it.copy(previewConfig = null, pendingDelete = name)
    }

    fun dismissDeleteConfig() = _state.update { it.copy(pendingDelete = null) }

    fun deleteConfig(name: String) {
        store.removeConfig(name)
        // Deleting the active entry moves the active slot to the newest one
        // left, so the loaded offsets have to follow it.
        _state.update {
            it.copy(
                configs = store.loadConfigs(),
                activeConfig = store.activeName(),
                offsets = store.activeOffsets(),
                pendingDelete = null,
                previewConfig = null,
                lastMessage = "已删除 $name",
            )
        }
    }

    // ---- Shizuku -----------------------------------------------------------

    fun refreshShizukuStatus() =
        _state.update { it.copy(shizukuStatus = repository.shizukuStatus) }

    // ---- run settings ------------------------------------------------------

    fun updateRunConfig(transform: (RunConfig) -> RunConfig) = _state.update { state ->
        val config = transform(state.runConfig)
        store.saveRunConfig(config)
        state.copy(runConfig = config)
    }

    // ---- manager selection -------------------------------------------------

    fun openManagerPicker() = _state.update { it.copy(managerDialogVisible = true) }

    fun dismissManagerPicker() = _state.update { it.copy(managerDialogVisible = false) }

    fun pickManager(manager: KsuManager) {
        updateRunConfig { it.copy(manager = manager) }
        dismissManagerPicker()
    }

    // ---- run cheese --------------------------------------------------------

    fun onRun() {
        val snapshot = _state.value
        if (snapshot.offsets == null || snapshot.running || snapshot.busy) return
        // Stellar can die between refreshes, and the run needs it; re-read it
        // and let the card explain a refusal instead of failing silently later.
        val status = repository.shizukuStatus
        if (status != ShizukuStatus.Ready) {
            _state.update { it.copy(shizukuStatus = status) }
            return
        }
        runExploit()
    }

    /**
     * Publish one configuration.
     *
     * A name the cloud already holds is left untouched, and that case is
     * deliberately not reported.
     */
    fun publishConfig(name: String) {
        val state = _state.value
        val config = store.loadConfigs().firstOrNull { it.name == name } ?: return
        // Published copies carry `source: import` — that is what they are for
        // whoever downloads them.
        val json = config.publishJson(state.deviceName, state.buildId)
        viewModelScope.launch {
            runCatching { repository.publishConfig(name, json) }
                .onSuccess { uploaded -> if (uploaded) appendLog("info: 已上传配置 $name") }
                .onFailure { error -> appendLog("error: 上传配置失败：${error.message}") }
        }
    }

    /** The UI has handed [CheeseUiState.shareRequest] to the system share sheet. */
    fun clearShareRequest() = _state.update { it.copy(shareRequest = null) }

    private fun runExploit() {
        val snapshot = _state.value
        val offsets = snapshot.offsets ?: return
        _state.update {
            it.copy(
                running = true,
                logLines = emptyList(),
                ksuOutcome = null,
                executionSheetVisible = true,
            )
        }
        runJob = viewModelScope.launch {
            val exit = repository.runCheese(
                offsets = offsets,
                config = snapshot.runConfig,
                onLine = { appendLog(it) },
            )
            val outcome = decideKsuOutcome(_state.value.logLines.map { it.text })
            val manager = snapshot.runConfig.manager.label
            appendLog(
                when (outcome) {
                    KsuOutcome.Activated -> "info: $manager 已激活（cheese 退出码 $exit）"
                    KsuOutcome.Failed -> "info: $manager late-load 失败（cheese 退出码 $exit）"
                    KsuOutcome.NoLibksud -> "info: 没找到 libksud.so（cheese 退出码 $exit）"
                    KsuOutcome.Unknown ->
                        "info: 未检测到 $manager 结果（cheese 退出码 $exit）。"
                },
            )
            // A configuration this device extracted itself is published as soon
            // as a run proves it works; the cloud keeps the first copy of a name.
            val publish = snapshot.activeConfig?.takeIf {
                outcome == KsuOutcome.Activated && offsets.source == ConfigSource.Boot
            }
            _state.update { it.copy(running = false, ksuOutcome = outcome) }
            runJob = null
            if (publish != null) publishConfig(publish)
        }
    }

    /**
     * Stop a run in progress.
     *
     * The remote process is killed before the coroutine is cancelled: the run is
     * parked in a blocking wait that only returns once that process is gone, so
     * cancelling alone would leave cheese running in the background.
     */
    fun cancelRun() {
        if (!_state.value.running) return
        repository.cancelCheese()
        runJob?.cancel()
        runJob = null
        appendLog("info: 已取消运行")
        _state.update { it.copy(running = false, ksuOutcome = null) }
    }

    /**
     * Every supported manager package that is installed.
     *
     * All of them are queried, not just the first hit: more than one can be
     * installed, and the run uses whichever one is selected in the run settings.
     */
    private fun detectInstalledManagers(application: Application): Set<String> =
        KsuManager.entries
            .map { it.packageName }
            .filterTo(mutableSetOf()) { packageName ->
                @Suppress("DEPRECATION")
                runCatching { application.packageManager.getApplicationInfo(packageName, 0) }
                    .isSuccess
            }

    private companion object {
        const val MAX_LOG_LINES = 2000
    }
}

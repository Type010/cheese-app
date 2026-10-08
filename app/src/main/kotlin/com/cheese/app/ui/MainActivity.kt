package com.cheese.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cheese.app.BuildConfig
import com.cheese.app.R
import com.cheese.app.domain.REPO_URL
import com.cheese.app.domain.jsonText
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel by viewModels<CheeseViewModel> {
        viewModelFactory { initializer { CheeseViewModel(application) } }
    }

    /** Local image: boot.img / payload.bin / OTA zip, copied to private storage. */
    private val imagePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) viewModel.parseImage(uri, viewModel.pendingKind)
        }

    /** offsets.json import. */
    private val jsonPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) viewModel.importOffsetsJson(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            LaunchedEffect(state.running) {
                if (state.running) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
            // The view model asks for a share once a run has succeeded; the
            // chooser itself can only be started from an activity.
            LaunchedEffect(state.shareRequest) {
                state.shareRequest?.let { name ->
                    shareConfig(name)
                    viewModel.clearShareRequest()
                }
            }
            CheeseApp(
                state = state,
                actions = CheeseActions(
                    onOpenLoadConfig = viewModel::openLoadConfig,
                    onOpenAdvanced = viewModel::openAdvanced,
                    onOpenAbout = viewModel::openAbout,
                    onOpenGitHub = {
                        runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                        }
                    },
                    onCloseScreen = viewModel::closeScreen,
                    onImportOffsetsJson = {
                        jsonPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                    },
                    onOpenCloudConfigs = viewModel::openCloudConfigs,
                    onDownloadCloudConfig = viewModel::downloadCloudConfig,
                    onParseOta = viewModel::openUrlDialog,
                    onParseLocal = viewModel::openLocalImageDialog,
                    onPickLocalImage = { kind ->
                        viewModel.chooseLocalImageKind(kind)
                        imagePicker.launch(arrayOf("*/*"))
                    },
                    onDismissLocalImage = viewModel::dismissLocalImageDialog,
                    onUrlChanged = viewModel::onUrlChanged,
                    onConfirmUrl = viewModel::confirmUrl,
                    onDismissUrl = viewModel::dismissUrlDialog,
                    onLoadConfig = viewModel::loadConfig,
                    onPreviewConfig = viewModel::previewConfig,
                    onDismissPreview = viewModel::dismissPreview,
                    onShareConfig = { name -> shareConfig(name) },
                    onRequestDeleteConfig = viewModel::requestDeleteConfig,
                    onDeleteConfig = viewModel::deleteConfig,
                    onDismissDeleteConfig = viewModel::dismissDeleteConfig,
                    onUpdateRunConfig = viewModel::updateRunConfig,
                    onOpenManagerPicker = viewModel::openManagerPicker,
                    onPickManager = viewModel::pickManager,
                    onDismissManagerPicker = viewModel::dismissManagerPicker,
                    onRun = viewModel::onRun,
                    onCancelRun = viewModel::cancelRun,
                    onShowExecutionSheet = { viewModel.setExecutionSheetVisible(true) },
                    onRootCmdChanged = { value ->
                        viewModel.updateRunConfig { it.copy(customRootCmd = value) }
                    },
                    onCloseExecutionSheet = { viewModel.setExecutionSheetVisible(false) },
                    onClearLog = viewModel::clearLog,
                    onNoticeShown = viewModel::clearNotice,
                ),
            )
        }
        setupSystemBars()
        handleDebugParseIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDebugParseIntent(intent)
    }

    /**
     * Debug builds only: let adb hand over an image path so the parse flow can be
     * driven from a script.
     *
     * ```sh
     * adb shell am start -n com.cheese.app/.ui.MainActivity \
     *   --es parse_path /data/data/com.cheese.app/files/kernel.img
     * ```
     */
    private fun handleDebugParseIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        // Publishing normally waits for a successful run, which a test device
        // cannot produce, so it can also be triggered directly.
        intent?.getStringExtra(EXTRA_PUBLISH_CONFIG)?.takeIf { it.isNotBlank() }?.let { name ->
            viewModel.publishConfig(name)
            return
        }
        val path = intent?.getStringExtra(EXTRA_PARSE_PATH)?.takeIf { it.isNotBlank() } ?: return
        viewModel.parseImage(Uri.fromFile(File(path)))
    }

    /**
     * Hand one configuration to another app. The JSON goes out both as a shared
     * `.json` file (through our FileProvider) and as plain text, so targets that
     * only take text still work.
     */
    private fun shareConfig(name: String) {
        val state = viewModel.state.value
        val config = state.configs.firstOrNull { it.name == name } ?: return
        val json = config.jsonText(
            device = state.deviceName,
            buildId = state.buildId,
            deviceRelease = state.kernelRelease,
        )
        runCatching {
            val dir = File(cacheDir, SHARE_DIR).apply { mkdirs() }
            val file = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            file.writeText(json)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, config.name)
                putExtra(Intent.EXTRA_TEXT, json)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.share_config_chooser)))
        }
    }

    /** Status/navigation bar icon colour follows the theme. */
    private fun setupSystemBars() {
        val controller = window.decorView.windowInsetsController ?: return
        val lightStatus = if (resources.getBoolean(R.bool.window_light_status_bar)) {
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        } else {
            0
        }
        val lightNavigation = if (resources.getBoolean(R.bool.window_light_navigation_bar)) {
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        } else {
            0
        }
        controller.setSystemBarsAppearance(
            lightStatus or lightNavigation,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    private companion object {
        const val EXTRA_PARSE_PATH = "parse_path"
        const val EXTRA_PUBLISH_CONFIG = "publish_config"

        /** Cache subdirectory exposed through the share FileProvider. */
        const val SHARE_DIR = "share"
    }
}

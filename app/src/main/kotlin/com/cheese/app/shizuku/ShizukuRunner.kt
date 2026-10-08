package com.cheese.app.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

/** Shizuku availability, as shown in the UI. */
enum class ShizukuStatus {
    NotRunning,
    PermissionRequired,
    Ready;

    val label: String
        get() = when (this) {
            NotRunning -> "未运行"
            PermissionRequired -> "需要授权"
            Ready -> "已就绪"
        }
}

/**
 * Runs commands with the Stellar/Shizuku service identity (shell or root).
 *
 * The app itself stays unprivileged; only the process it asks for runs as the
 * service user, which is what cheese was originally verified against.
 */
class ShizukuRunner(context: Context) {

    private var listener: (() -> Unit)? = null

    /** The process [runScript] is waiting on, so [cancel] can kill it. */
    @Volatile
    private var activeRemote: IRemoteProcess? = null

    private val binderReceived = Shizuku.OnBinderReceivedListener { listener?.invoke() }
    private val binderDead = Shizuku.OnBinderDeadListener { listener?.invoke() }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == PERMISSION_REQUEST) listener?.invoke()
        }

    init {
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }
    }

    fun setListener(value: (() -> Unit)?) {
        listener = value
    }

    fun status(): ShizukuStatus = runCatching {
        when {
            !Shizuku.pingBinder() -> ShizukuStatus.NotRunning
            // Shell (2000) or root (0): both are unfiltered contexts, and some
            // Shizuku variants (Stellar) run their server as root.
            Shizuku.getUid() != Process.SHELL_UID && Shizuku.getUid() != 0 ->
                ShizukuStatus.NotRunning
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED ->
                ShizukuStatus.PermissionRequired

            else -> ShizukuStatus.Ready
        }
    }.getOrElse { ShizukuStatus.NotRunning }

    fun requestPermission() {
        runCatching {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
            ) {
                Shizuku.requestPermission(PERMISSION_REQUEST)
            } else {
                listener?.invoke()
            }
        }.onFailure { listener?.invoke() }
    }

    /**
     * Run one shell script as the Stellar service user (shell or root).
     *
     * Shizuku 13 dropped `Shizuku.newProcess`, so we take the service binder from
     * [Shizuku.getBinder] and call it ourselves. The child inherits the server's
     * identity — uid 2000/0, empty seccomp filter — which is what lets cheese's
     * final vhangup() syscall survive.
     *
     * @return the exit code, or -1 when the script never ran.
     */
    suspend fun runScript(
        script: String,
        workDir: String,
        timeoutSeconds: Long,
        onLine: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        if (status() != ShizukuStatus.Ready) {
            onLine("error: Stellar 不可用（${status().label}）")
            return@withContext -1
        }
        val binder = runCatching { Shizuku.getBinder() }.getOrNull()
        if (binder == null || !binder.pingBinder()) {
            onLine("error: 拿不到 Stellar 服务 binder")
            return@withContext -1
        }
        val remote = runCatching {
            IShizukuService.Stub.asInterface(binder)
                .newProcess(arrayOf(SHELL, "-c", script), null, workDir)
        }.getOrElse { error ->
            onLine("error: Stellar 启动进程失败：${error.message}")
            return@withContext -1
        }
        try {
            activeRemote = remote
            val streams = runCatching {
                listOfNotNull(
                    remote.inputStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) },
                    remote.errorStream?.let { ParcelFileDescriptor.AutoCloseInputStream(it) },
                )
            }.getOrElse { error ->
                onLine("error: 无法读取进程输出：${error.message}")
                return@withContext -1
            }
            val readers = streams.map { stream ->
                Thread {
                    runCatching {
                        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                            while (true) onLine(reader.readLine() ?: break)
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
            val exited = runCatching { remote.waitForTimeout(timeoutSeconds, SECONDS_UNIT) }
                .getOrElse { error ->
                    onLine("error: 等待进程结束失败：${error.message}")
                    false
                }
            if (!exited) {
                onLine("error: 超时 ${timeoutSeconds}s，已终止")
                return@withContext -1
            }
            readers.forEach { it.join(READ_GRACE_MS) }
            runCatching { remote.exitValue() }.getOrDefault(-1)
        } finally {
            activeRemote = null
            runCatching { remote.destroy() }
        }
    }

    /**
     * Kill the process [runScript] is currently waiting on, if any.
     *
     * Cancelling the coroutine alone is not enough: `waitForTimeout` is a
     * blocking binder call, so it only notices cancellation once the process
     * has exited. Destroying it here is what makes the wait return.
     */
    fun cancel() {
        runCatching { activeRemote?.destroy() }
    }

    fun close() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionResult)
        }
        listener = null
    }

    private companion object {
        const val PERMISSION_REQUEST = 4816

        /** Shell used to run the generated script. */
        const val SHELL = "/system/bin/sh"

        /** Stellar parses this with `TimeUnit.valueOf`, so it must be exact. */
        const val SECONDS_UNIT = "SECONDS"

        /** How long to wait for the output readers to drain after exit. */
        const val READ_GRACE_MS = 3_000L
    }
}

package com.cheese.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.cheese.app.data.cloud.CloudConfigs
import com.cheese.app.data.cloud.CloudEntry
import com.cheese.app.data.ota.OtaPayloadExtractor
import com.cheese.app.domain.ConfigSource
import com.cheese.app.domain.DEFAULT_KERNEL_BASE
import com.cheese.app.domain.imageMatchesDevice
import com.cheese.app.domain.rejectionReason
import com.cheese.app.shizuku.ShizukuRunner
import com.cheese.app.shizuku.ShizukuStatus
import com.cheese.app.domain.CheeseOffsets
import com.cheese.app.domain.ParseOutcome
import com.cheese.app.domain.RunConfig
import com.cheese.app.domain.effectiveLogPath
import com.cheese.app.domain.effectiveRootCmd
import com.cheese.app.domain.formatHex
import com.cheese.app.domain.parseHexOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Drives the two native binaries:
 *
 * - `libextract.so` (Rust) computes INIT_TASK_OFF / SWAPPER_PG_DIR_OFF from a kernel image;
 * - `libcheese.so` (cheese) escalates, then runs `ROOT_CMD` to load KernelSU.
 *
 * Both ship as `lib*.so` in jniLibs, so the installer extracts them into
 * nativeLibraryDir, where they can be executed directly.
 */
class CheeseRepository(private val context: Context) {

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    /** Shell-identity execution through Stellar (the exploit's original context). */
    private val shizuku = ShizukuRunner(context)

    /** The shared configuration bucket. */
    private val cloud = CloudConfigs()

    val shizukuStatus: ShizukuStatus get() = shizuku.status()

    fun onShizukuStatusChanged(listener: (() -> Unit)?) = shizuku.setListener(listener)

    fun requestShizukuPermission() = shizuku.requestPermission()

    /** Kill the cheese run started by [runCheese], if one is still going. */
    fun cancelCheese() = shizuku.cancel()

    /** Parse an image picked through SAF; [source] says which entry point it came from. */
    suspend fun parseImage(
        uri: Uri,
        source: ConfigSource,
        onLine: (String) -> Unit,
    ): ParseOutcome =
        withContext(Dispatchers.IO) {
            val label = queryDisplayName(uri)
            val target = File(context.cacheDir, "input.img")
            onLine("info: 正在读取 $label")
            val copied = runCatching {
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法打开所选文件")
                stream.use { input -> target.outputStream().use { input.copyTo(it) } }
            }.getOrElse { error ->
                return@withContext ParseOutcome.Failure("读取文件失败：${error.message}")
            }
            if (copied == 0L) return@withContext ParseOutcome.Failure("文件是空的")
            onLine("info: 已复制 ${copied / 1048576} MB 到私有目录")
            runExtractor(target, source, onLine)
        }

    /**
     * Pull the boot image out of a full OTA package URL (only that partition is
     * downloaded, via HTTP range requests) and parse it.
     */
    suspend fun parseUrl(url: String, onLine: (String) -> Unit): ParseOutcome =
        withContext(Dispatchers.IO) {
            val extracted = runCatching {
                OtaPayloadExtractor.extractPartitions(url, context.filesDir, onLine)
            }.getOrElse { error ->
                return@withContext ParseOutcome.Failure("下载/解包失败：${error.message}")
            }
            onLine("info: 已提取 ${extracted.bootFile.name}")
            runExtractor(extracted.bootFile, ConfigSource.Boot, onLine)
        }

    /** Import offsets from a JSON file (our config, a report, or a flat offsets file). */
    suspend fun importOffsetsJson(uri: Uri, onLine: (String) -> Unit): ParseOutcome =
        withContext(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.readBytes().toString(Charsets.UTF_8)
                } ?: throw IllegalStateException("无法打开所选文件")
            }.getOrElse { error ->
                return@withContext ParseOutcome.Failure("读取文件失败：${error.message}")
            }
            parseConfigJson(text, ConfigSource.Import, onLine)
        }

    /** Download one shared configuration and turn it into offsets. */
    suspend fun downloadCloudConfig(key: String, onLine: (String) -> Unit): ParseOutcome =
        withContext(Dispatchers.IO) {
            onLine("info: 正在下载 $key")
            val text = runCatching { cloud.fetch(key) }.getOrElse { error ->
                return@withContext ParseOutcome.Failure("下载失败：${error.message}")
            }
            parseConfigJson(text, ConfigSource.Import, onLine)
        }

    /** List the configurations published in the cloud bucket. */
    suspend fun listCloudConfigs(): List<CloudEntry> =
        withContext(Dispatchers.IO) { cloud.list() }

    /**
     * Publish one configuration under [key], unless the cloud already has that
     * name — an existing entry is left alone and the caller stays quiet about it.
     *
     * @return true when something was actually uploaded.
     */
    suspend fun publishConfig(key: String, json: String): Boolean =
        withContext(Dispatchers.IO) {
            if (cloud.exists(key)) return@withContext false
            cloud.upload(key, json)
            true
        }

    /**
     * Refuse offsets this device must not use.
     *
     * A configuration is only created for offsets that pass the structure check
     * and come from the kernel this device runs, so nothing is stored that would
     * need a warning next to it later.
     */
    private fun rejectIfUnusable(offsets: CheeseOffsets): ParseOutcome? =
        offsets.rejectionReason()?.let { ParseOutcome.Failure("$it，未生成配置") }

    /** Read the two offsets (and what we can of the metadata) out of a config file. */
    private fun parseConfigJson(
        text: String,
        source: ConfigSource,
        onLine: (String) -> Unit,
    ): ParseOutcome {
        val root = runCatching { JSONObject(text) }.getOrElse {
            return ParseOutcome.Failure("不是合法的 JSON 文件")
        }
        val nested = root.optJSONObject("offsets")
        val initTask = readOffset(root, nested, "init_task", "INIT_TASK_OFF")
        val swapper = readOffset(root, nested, "swapper_pg_dir", "SWAPPER_PG_DIR_OFF")
        if (initTask == null || swapper == null) {
            return ParseOutcome.Failure("文件里没有 init_task / swapper_pg_dir")
        }
        val kernelBase = readOffset(root, nested, "kernel_base", "KERNEL_BASE")
        // `release` is the spelling a flat offsets file uses; our own configs
        // carry it as `kernel_release`.
        val release = listOf("release", "kernel_release")
            .map { root.optString(it) }
            .firstOrNull { it.isNotBlank() && it != "null" }
        // Our own config files store `verified` as a plain boolean; the
        // extractor's report nests it as {"all": …}. Accept both, or a
        // config written by this app would read back as unverified.
        val verified = when (val flag = root.opt("verified")) {
            is JSONObject -> flag.optBoolean("all")
            is Boolean -> flag
            else -> false
        }
        onLine(
            "info: 导入 INIT_TASK_OFF=${formatHex(initTask)} " +
                "SWAPPER_PG_DIR_OFF=${formatHex(swapper)}",
        )
        val offsets = CheeseOffsets(
            initTaskOff = initTask,
            swapperPgDirOff = swapper,
            kernelBase = kernelBase ?: DEFAULT_KERNEL_BASE,
            imageRelease = release,
            source = source,
            releaseMatches = imageMatchesDevice(release),
            verified = verified,
            savedAtMillis = System.currentTimeMillis(),
        )
        rejectIfUnusable(offsets)?.let { return it }
        // The stored text must not contradict what the app decided this config
        // is: a file that claims `boot` was not extracted here, so it is an
        // import, and the copy we keep has to say so too.
        val declared = ConfigSource.fromId(root.optString("source"))
        return ParseOutcome.Success(offsets, raw = text.takeIf { declared == source })
    }

    /** Run the extractor on a local image and turn its report into offsets. */
    private fun runExtractor(image: File, source: ConfigSource, onLine: (String) -> Unit): ParseOutcome {
        val binary = File(nativeDir, EXTRACT_BINARY)
        if (!binary.isFile) {
            return ParseOutcome.Failure("缺少 $EXTRACT_BINARY（请重新安装 APK）")
        }
        val outFile = File(context.cacheDir, "offsets.json")
        outFile.delete()
        val exit = runProcess(
            command = listOf(
                binary.absolutePath,
                image.absolutePath,
                "--format", "json",
                "--out", outFile.absolutePath,
                "--work-dir", context.cacheDir.absolutePath,
            ),
            workDir = context.cacheDir,
            env = emptyMap(),
            onLine = onLine,
        )
        if (exit != 0 || !outFile.isFile) {
            return ParseOutcome.Failure("解析失败（退出码 $exit）", exit)
        }
        return parseReport(outFile.readText(), source, onLine)
    }

    /** Turn the extractor's JSON report into [CheeseOffsets]. */
    private fun parseReport(
        json: String,
        source: ConfigSource,
        onLine: (String) -> Unit,
    ): ParseOutcome {
        val root = runCatching { JSONObject(json) }.getOrElse {
            return ParseOutcome.Failure("提取器输出不是合法 JSON")
        }
        val offsets = root.optJSONObject("offsets") ?: return ParseOutcome.Failure("报告里没有 offsets")
        if (offsets.isNull("init_task") || offsets.isNull("swapper_pg_dir")) {
            val missing = root.optJSONArray("missing")?.toString() ?: "init_task / swapper_pg_dir"
            return ParseOutcome.Failure("镜像里缺少符号：$missing")
        }
        val initTask = offsets.getLong("init_task")
        val swapper = offsets.getLong("swapper_pg_dir")
        val verified = root.optJSONObject("verified")?.optBoolean("all") ?: false
        val checks = root.optJSONObject("checks")
        val release = root.optString("release").takeIf { it.isNotBlank() && it != "null" }
        if (release != null) onLine("info: 镜像内核版本 $release")
        onLine(
            "info: INIT_TASK_OFF=${formatHex(initTask)} " +
                "SWAPPER_PG_DIR_OFF=${formatHex(swapper)} " +
                if (verified) "（结构校验通过）" else "（结构校验未通过）",
        )
        val kernelBase = root.optLong("kernel_phys_load").takeIf { it > 0 } ?: DEFAULT_KERNEL_BASE
        val result = CheeseOffsets(
            initTaskOff = initTask,
            swapperPgDirOff = swapper,
            kernelBase = kernelBase,
            imageRelease = release,
            source = source,
            releaseMatches = imageMatchesDevice(release),
            verified = verified,
            initTaskDetail = checks?.optString("init_task")?.takeIf { it != "null" },
            swapperDetail = checks?.optString("swapper_pg_dir")?.takeIf { it != "null" },
            savedAtMillis = System.currentTimeMillis(),
        )
        rejectIfUnusable(result)?.let { return it }
        return ParseOutcome.Success(result)
    }

    /**
     * Run cheese with the two offsets in the environment; manager activation is
     * left to `ROOT_CMD`. The run goes through Stellar, so cheese inherits the
     * service's identity (shell/root, no seccomp filter) — running as the app
     * always dies at the final vhangup() syscall.
     */
    suspend fun runCheese(
        offsets: CheeseOffsets,
        config: RunConfig,
        onLine: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val binary = File(nativeDir, CHEESE_BINARY)
        if (!binary.isFile) {
            onLine("error: 缺少 $CHEESE_BINARY（请重新安装 APK）")
            return@withContext 127
        }
        if (shizukuStatus != ShizukuStatus.Ready) {
            onLine("error: Stellar 未就绪，无法执行（vhangup 阶段需要 shell 身份）")
            return@withContext -1
        }
        onLine(
            "info: 偏移 ${formatHex(offsets.initTaskOff)} / ${formatHex(offsets.swapperPgDirOff)}" +
                "，KERNEL_BASE ${formatHex(offsets.kernelBase)}",
        )
        val logPath = config.effectiveLogPath()
        onLine("info: cheese 日志 $logPath")
        val env = buildMap {
            put("CHEESE_INIT_TASK_OFF", formatHex(offsets.initTaskOff))
            put("CHEESE_SWAPPER_PG_DIR_OFF", formatHex(offsets.swapperPgDirOff))
            put("CHEESE_KERNEL_BASE", formatHex(offsets.kernelBase))
            put("CHEESE_LOG", logPath)
            put("ROOT_CMD", config.effectiveRootCmd())
            config.attempt.trim().takeIf { it.isNotEmpty() }?.let { put("CHEESE_ATTEMPT", it) }
            config.phyAddr.trim().takeIf { it.isNotEmpty() }?.let { put("CHEESE_PHYADDR", it) }
            if (config.noRetry) put("CHEESE_NO_RETRY", "1")
        }
        shizuku.runScript(
            script = buildShellScript(env, binary.absolutePath),
            workDir = SHELL_TMP_DIR,
            timeoutSeconds = RUN_TIMEOUT_SECONDS,
            onLine = onLine,
        )
    }

    /**
     * `export KEY=VALUE` lines followed by `exec <binary>`, for `/system/bin/sh
     * -c`. The environment is built here rather than passed to newProcess so
     * the child keeps the service's own environment (PATH, ANDROID_ROOT, …)
     * and only the CHEESE_* knobs are overridden.
     */
    private fun buildShellScript(env: Map<String, String>, binary: String): String = buildString {
        for ((key, value) in env) {
            append("export ").append(key).append('=').append(shellQuote(value)).append('\n')
        }
        append("exec ").append(shellQuote(binary)).append('\n')
    }

    /** Single-quote a value for `sh`, escaping embedded quotes. */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Run a child process, streaming its merged stdout/stderr line by line. */
    private fun runProcess(
        command: List<String>,
        workDir: File,
        env: Map<String, String>,
        onLine: (String) -> Unit,
        timeoutSeconds: Long = 1800,
    ): Int {
        val builder = ProcessBuilder(command)
            .directory(workDir)
            .redirectErrorStream(true)
        builder.environment().putAll(env)
        val process = try {
            builder.start()
        } catch (error: Exception) {
            onLine("error: 无法启动 ${command.first()}: ${error.message}")
            return 127
        }
        try {
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "reading process output failed", error)
        }
        val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            onLine("error: 超时 ${timeoutSeconds}s，已终止")
            return -1
        }
        return process.exitValue()
    }

    /** Read one offset from either the nested `offsets` object or the root. */
    private fun readOffset(root: JSONObject, nested: JSONObject?, vararg keys: String): Long? {
        for (key in keys) {
            for (source in listOfNotNull(nested, root)) {
                if (source.isNull(key)) continue
                source.opt(key)?.let { value ->
                    when (value) {
                        is Number -> return value.toLong()
                        is String -> parseHexOrNull(value)?.let { return it }
                    }
                }
            }
        }
        return null
    }

    private fun queryDisplayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment ?: "image"
    }

    private companion object {
        const val TAG = "CheeseRepository"
        const val EXTRACT_BINARY = "libextract.so"
        const val CHEESE_BINARY = "libcheese.so"

        /** Writable by the shell/root identity Stellar runs as. */
        const val SHELL_TMP_DIR = "/data/local/tmp"
        const val RUN_TIMEOUT_SECONDS = 900L
    }
}

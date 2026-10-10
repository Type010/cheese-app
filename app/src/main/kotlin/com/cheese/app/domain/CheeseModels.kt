package com.cheese.app.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Default `KERNEL_PHYS_BASE` for the SM8550 family; editable in the UI. */
const val DEFAULT_KERNEL_BASE: Long = 0xA800_0000L

/**
 * KMI passed to KernelSU `late-load`.
 *
 * Deliberately pinned to android13-5.15: this device is 5.15 GKI, and no kernel
 * detection is performed.
 */
const val KSU_LATE_LOAD_KMI: String = "android13-5.15"

/** Privilege managers the built-in `ROOT_CMD` knows how to late-load. */
enum class KsuManager(val packageName: String, val label: String) {
    KernelSU("me.weishu.kernelsu", "KernelSU"),
    ReSukiSU("com.resukisu.resukisu", "ReSukiSU"),
}

/** Where cheese writes its logs unless the user names another path. */
const val DEFAULT_LOG_DIR: String = "/sdcard/Download/cheese-app"

/** Project home page, linked from the About screen. */
const val REPO_URL: String = "https://github.com/Type010/cheese-app"

/** Base URL of the shared configurations bucket (public S3 list + GET). */
const val CLOUD_BASE_URL: String = "https://123.123.123/cheese"

/** User agent every cloud request carries. */
const val CLOUD_USER_AGENT: String = "123"

/** The kernel release this device is running (`uname -r`). */
fun deviceKernelRelease(): String = System.getProperty("os.version").orEmpty().trim()

/**
 * Whether an image release string names the kernel this device runs.
 *
 * Null when either side is unknown, which is not a failure — a config may simply
 * not record where it came from.
 */
fun imageMatchesDevice(imageRelease: String?): Boolean? {
    val image = imageRelease?.trim().orEmpty()
    val device = deviceKernelRelease()
    if (image.isEmpty() || device.isEmpty()) return null
    return image == device
}

/**
 * Why these offsets must not be used on this device, or null when they may.
 *
 * A configuration is only ever created for offsets that pass the structure check
 * and come from the kernel this device actually runs; anything else is refused
 * rather than stored with a flag nobody acts on.
 */
fun CheeseOffsets.rejectionReason(): String? = when {
    // A stored entry that carries no offsets is not a configuration at all; it
    // stays in the list so it can be seen and deleted, but it never loads.
    initTaskOff <= 0L || swapperPgDirOff <= 0L -> "配置不合法"
    !verified -> "结构校验未通过"
    imageRelease.isNullOrBlank() -> "镜像没有报告内核版本"
    releaseMatches != true ->
        "内核版本与本机不一致（镜像 $imageRelease，本机 ${deviceKernelRelease()}）"

    else -> null
}

/** True when [rejectionReason] is null. */
val CheeseOffsets.isUsable: Boolean get() = rejectionReason() == null

/** Where a configuration came from. Stored in the file as the `source` key.
 *
 * Only two kinds exist: everything this device extracts itself — a full OTA
 * package, a boot image or a payload — comes out of the boot partition and is
 * [Boot]; everything handed to the app as JSON is [Import].
 */
enum class ConfigSource(val id: String, val label: String) {
    Boot("boot", "boot 提取"),
    Import("import", "导入");

    companion object {
        /**
         * Anything that does not claim to come from boot is an import — including
         * a file with no `source` key at all, and the older spellings.
         */
        fun fromId(id: String?): ConfigSource = when (id?.trim()?.lowercase()) {
            Boot.id, "link", "payload" -> Boot
            else -> Import
        }
    }
}

/**
 * Default `ROOT_CMD`: what cheese's root child runs once it reaches uid=0 —
 * locate the selected manager's libksud.so under /data/app, echo its path,
 * late-load it with the pinned KMI, and print a marker the app can match.
 * The lookup filters by package first, then falls back to any libksud.so.
 */
fun defaultRootCmd(manager: KsuManager): String =
    "SO=\$(find /data/app -type f -path \"*${manager.packageName}*\" -name libksud.so 2>/dev/null | head -1); " +
        "[ -z \"\$SO\" ] && SO=\$(find /data/app -type f -name libksud.so 2>/dev/null | head -1); " +
        "[ -z \"\$SO\" ] && { echo NO_LIBKSUD; exit 1; }; " +
        "echo INJECT_SO=\$SO; " +
        "\$SO late-load --kmi $KSU_LATE_LOAD_KMI" +
        " && echo KSU_INSMOD_OK || echo KSU_INSMOD_FAIL"

/** Offsets for one kernel, plus where they came from. */
data class CheeseOffsets(
    val initTaskOff: Long,
    val swapperPgDirOff: Long,
    val kernelBase: Long = DEFAULT_KERNEL_BASE,
    /** Kernel release string found in the image, compared against `uname -r`. */
    val imageRelease: String? = null,
    /** How these offsets were obtained. */
    val source: ConfigSource = ConfigSource.Import,
    /**
     * Whether the image these offsets came from is the kernel this device runs.
     * Null when the source carried no release string at all.
     */
    val releaseMatches: Boolean? = null,
    /** Both structure checks (init_task and swapper_pg_dir) passed. */
    val verified: Boolean = true,
    val initTaskDetail: String? = null,
    val swapperDetail: String? = null,
    val savedAtMillis: Long = 0L,
)

/** Result of one image parse. */
sealed interface ParseOutcome {
    data class Success(val offsets: CheeseOffsets, val raw: String? = null) : ParseOutcome
    data class Failure(val message: String, val exitCode: Int? = null) : ParseOutcome
}

/** Everything cheese needs besides the two offsets. */
data class RunConfig(
    /**
     * Run cheese through Shizuku (shell identity, no seccomp filter) instead of
     * in this process. The filter this app inherits from zygote is what an
     * in-process run clears itself, so the Shizuku path skips that stage.
     */
    val viaShizuku: Boolean = false,
    /** Manager whose libksud.so the built-in ROOT_CMD late-loads. */
    val manager: KsuManager = KsuManager.KernelSU,
    /**
     * Custom ROOT_CMD. Empty means the built-in command for [manager] is used;
     * the built-in one is never displayed, only a custom command is.
     */
    val customRootCmd: String = "",
    /** `CHEESE_ATTEMPT`: start from this entry of the candidate physical table. */
    val attempt: String = "",
    /** `CHEESE_PHYADDR`: use this candidate physical address directly. */
    val phyAddr: String = "",
    /** `CHEESE_NO_RETRY`: try a single candidate only. */
    val noRetry: Boolean = false,
)

/** The command actually handed to cheese. */
fun RunConfig.effectiveRootCmd(): String = customRootCmd.ifBlank { defaultRootCmd(manager) }

/**
 * `CHEESE_LOG` for one run: `cheese-<yyyyMMdd-HHmmss>.log` under
 * [DEFAULT_LOG_DIR] — a fresh file each time, so a run never overwrites the
 * previous one's record.
 */
fun cheeseLogPath(nowMillis: Long = System.currentTimeMillis()): String =
    "$DEFAULT_LOG_DIR/cheese-${logStamp(nowMillis)}.log"

private fun logStamp(nowMillis: Long): String =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMillis))

/** KernelSU outcome, decided from the run log. */
enum class KsuOutcome {
    Activated,
    Failed,
    NoLibksud,
    Unknown;

    val label: String
        get() = when (this) {
            Activated -> "KernelSU 已激活"
            Failed -> "late-load 失败"
            NoLibksud -> "没找到 libksud.so"
            Unknown -> "结果未知"
        }
}

/** Log line category; drives the colour in the log panel. */
enum class LogTone { Normal, Stage, Success, Failure, Hint }

data class LogLine(val text: String, val tone: LogTone = LogTone.Normal)

/** Rough classification of one cheese output line. */
fun classifyLogLine(line: String): LogTone = when {
    line.contains("KSU_INSMOD_OK") -> LogTone.Success
    line.contains("KSU_INSMOD_FAIL") || line.contains("NO_LIBKSUD") -> LogTone.Failure
    line.contains("error", ignoreCase = true) ||
        line.contains("failed", ignoreCase = true) ||
        line.contains("MISMATCH") ||
        line.contains("不一致") -> LogTone.Failure
    line.contains("root ok") || line.contains("KASLR slide") -> LogTone.Success
    Regex("""^\[\d/\d]""").containsMatchIn(line) -> LogTone.Stage
    line.startsWith("info:") || line.startsWith("[*]") -> LogTone.Hint
    else -> LogTone.Normal
}

/** Final KernelSU result; the last marker in the log wins. */
fun decideKsuOutcome(lines: List<String>): KsuOutcome {
    val tail = lines.takeLast(400)
    tail.lastOrNull { it.contains("KSU_INSMOD_OK") }?.let { return KsuOutcome.Activated }
    tail.lastOrNull { it.contains("NO_LIBKSUD") }?.let { return KsuOutcome.NoLibksud }
    tail.lastOrNull { it.contains("KSU_INSMOD_FAIL") }?.let { return KsuOutcome.Failed }
    return KsuOutcome.Unknown
}

/** Lower-case hex, the spelling cheese's own examples use. */
fun formatHex(value: Long): String = "0x" + value.toString(16)

/** Parse a hex (`0x…`) or decimal offset typed by the user. */
fun parseHexOrNull(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("0x", ignoreCase = true)) {
        trimmed.substring(2).toLongOrNull(16)
    } else {
        trimmed.toLongOrNull() ?: trimmed.toLongOrNull(16)
    }
}

/** One saved configuration; the name doubles as the export file name. */
data class SavedConfig(
    val name: String,
    val initTaskOff: Long,
    val swapperPgDirOff: Long,
    val kernelBase: Long = DEFAULT_KERNEL_BASE,
    val release: String? = null,
    val verified: Boolean = false,
    /** Where it came from: this device's own boot image, or an import. */
    val source: ConfigSource = ConfigSource.Import,
    val savedAtMillis: Long = 0L,
    /** Exact content of the configuration file, shown in the preview. */
    val raw: String = "",
) {
    fun toOffsets(): CheeseOffsets = CheeseOffsets(
        initTaskOff = initTaskOff,
        swapperPgDirOff = swapperPgDirOff,
        kernelBase = kernelBase,
        imageRelease = release,
        source = source,
        releaseMatches = imageMatchesDevice(release),
        verified = verified,
        savedAtMillis = savedAtMillis,
    )
}

/** Which kind of image the local picker should ask for. */
enum class LocalImageKind(val label: String) {
    Boot("boot.img"),
    Payload("payload.bin"),
}

/** Configuration file name: `offsets-<model>-<build id>.json`. */
fun configFileName(model: String, buildId: String): String {
    val suffix = listOf(model, buildId)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("-")
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    return if (suffix.isEmpty()) "offsets.json" else "offsets-$suffix.json"
}

/**
 * Configuration file content, as written to disk and shown in the preview.
 *
 * [kernelRelease] is the kernel the offsets belong to — the one inside the image
 * they were extracted from, falling back to this device's only when the image
 * never reported one. It is what tells two configurations for one device apart.
 */
fun buildConfigJson(
    name: String,
    device: String,
    buildId: String,
    kernelRelease: String,
    offsets: CheeseOffsets,
    source: ConfigSource = offsets.source,
): String {
    fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    return buildString {
        appendLine("{")
        appendLine("  \"name\": ${quote(name)},")
        appendLine("  \"device\": ${quote(device)},")
        appendLine("  \"build\": ${quote(buildId)},")
        appendLine("  \"source\": ${quote(source.id)},")
        appendLine("  \"kernel_release\": ${quote(kernelRelease)},")
        appendLine("  \"kernel_base\": ${quote(formatHex(offsets.kernelBase))},")
        appendLine("  \"init_task\": ${offsets.initTaskOff},")
        appendLine("  \"init_task_hex\": ${quote(formatHex(offsets.initTaskOff))},")
        appendLine("  \"swapper_pg_dir\": ${offsets.swapperPgDirOff},")
        appendLine("  \"swapper_pg_dir_hex\": ${quote(formatHex(offsets.swapperPgDirOff))},")
        appendLine("  \"verified\": ${offsets.verified}")
        append("}")
    }
}

/**
 * The text of one configuration: what was stored, or - for entries written
 * before the raw text was kept - the same JSON regenerated from its fields.
 * [deviceRelease] is only the fallback for entries that never recorded one.
 */
fun SavedConfig.jsonText(device: String, buildId: String, deviceRelease: String): String =
    raw.ifBlank { buildConfigJson(name, device, buildId, release ?: deviceRelease, toOffsets()) }

/**
 * The text to publish to the cloud: always regenerated, and with `source`
 * rewritten to [ConfigSource.Import] — which is what it becomes for whoever
 * downloads it, and the only thing their app would accept anyway.
 */
fun SavedConfig.publishJson(device: String, buildId: String): String = buildConfigJson(
    name = name,
    device = device,
    buildId = buildId,
    kernelRelease = release.orEmpty(),
    offsets = toOffsets(),
    source = ConfigSource.Import,
)

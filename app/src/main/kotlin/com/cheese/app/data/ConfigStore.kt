package com.cheese.app.data

import android.content.Context
import com.cheese.app.domain.CheeseOffsets
import com.cheese.app.domain.ConfigSource
import com.cheese.app.domain.DEFAULT_KERNEL_BASE
import com.cheese.app.domain.KsuManager
import com.cheese.app.domain.RunConfig
import com.cheese.app.domain.SavedConfig
import com.cheese.app.domain.isUsable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the parsed configurations and the run settings.
 *
 * Every successful parse or import is stored as one configuration; an identical
 * one is not stored twice. The active configuration is the one the run uses.
 */
class ConfigStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("cheese", Context.MODE_PRIVATE)

    /** Configurations are also written here, one file per entry. */
    private val configDir: File get() = File(context.filesDir, "configs").apply { mkdirs() }

    fun loadConfigs(): List<SavedConfig> {
        val raw = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrElse { return emptyList() }
        // Each entry is read on its own: one unreadable entry must not take the
        // rest of the list with it, and an entry missing its offsets still gets
        // listed so it can be seen and deleted — it just cannot be loaded.
        return (0 until array.length()).mapNotNull { index ->
            runCatching { array.optJSONObject(index)?.let(::toConfig) }.getOrNull()
        }
    }

    /**
     * Returns true when the list changed: a new entry, or one whose stored
     * content was refreshed.
     */
    fun saveConfig(config: SavedConfig): Boolean {
        val configs = loadConfigs().toMutableList()
        val existing = configs.indexOfFirst { it.name == config.name }
        if (existing >= 0) {
            // Compare the stored text as well, not just the offsets: a config
            // written by an older build keeps its old JSON in `raw`, and
            // re-parsing the same image has to replace that text or the preview
            // keeps showing fields the current format no longer has.
            if (configs[existing].sameContentAs(config)) return false
            configs[existing] = config
        } else {
            configs.add(0, config)
        }
        persist(configs)
        // The file mirrors the preview text; nothing reads it back.
        runCatching { File(configDir, config.name).writeText(config.raw) }
        return true
    }

    /**
     * Drop one configuration.
     *
     * Removing the active one leaves nothing loaded — it never falls back to
     * another configuration, so the home card goes back to "nothing loaded"
     * instead of silently switching to offsets the user did not pick.
     */
    fun removeConfig(name: String) {
        val configs = loadConfigs().filterNot { it.name == name }
        persist(configs)
        runCatching { File(configDir, name).delete() }
        if (activeName() == name) setActive(null)
    }

    fun activeName(): String? = prefs.getString(KEY_ACTIVE, null)

    fun setActive(name: String?) {
        prefs.edit().apply {
            if (name == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, name)
        }.apply()
    }

    /**
     * Offsets of the active configuration, or null when nothing usable is
     * loaded. A configuration stored before these rules existed, or one whose
     * kernel no longer matches after an update, is refused here too.
     */
    fun activeOffsets(): CheeseOffsets? {
        val name = activeName() ?: return null
        val config = loadConfigs().firstOrNull { it.name == name } ?: return null
        return config.toOffsets().takeIf { it.isUsable }
    }

    fun loadRunConfig(): RunConfig {
        val raw = prefs.getString(KEY_RUN_CONFIG, null) ?: return RunConfig()
        return runCatching {
            val json = JSONObject(raw)
            RunConfig(
                viaShizuku = json.optBoolean("via_shizuku", false),
                manager = KsuManager.entries.firstOrNull { it.name == json.optString("manager") }
                    ?: KsuManager.KernelSU,
                customRootCmd = json.optString("custom_root_cmd"),
                attempt = json.optString("attempt"),
                phyAddr = json.optString("phy_addr"),
                noRetry = json.optBoolean("no_retry", false),
            )
        }.getOrElse { RunConfig() }
    }

    fun saveRunConfig(config: RunConfig) {
        val json = JSONObject().apply {
            put("via_shizuku", config.viaShizuku)
            put("manager", config.manager.name)
            put("custom_root_cmd", config.customRootCmd)
            put("attempt", config.attempt)
            put("phy_addr", config.phyAddr)
            put("no_retry", config.noRetry)
        }
        prefs.edit().putString(KEY_RUN_CONFIG, json.toString()).apply()
    }

    private fun persist(configs: List<SavedConfig>) {
        val array = JSONArray()
        configs.forEach { config ->
            array.put(
                JSONObject().apply {
                    put("name", config.name)
                    put("init_task", config.initTaskOff)
                    put("swapper_pg_dir", config.swapperPgDirOff)
                    put("kernel_base", config.kernelBase)
                    put("release", config.release.orEmpty())
                    put("verified", config.verified)
                    put("source", config.source.id)
                    put("saved_at", config.savedAtMillis)
                    put("raw", config.raw)
                },
            )
        }
        prefs.edit().putString(KEY_CONFIGS, array.toString()).apply()
    }

    private fun toConfig(json: JSONObject) = SavedConfig(
        name = json.optString("name"),
        initTaskOff = json.optLong("init_task"),
        swapperPgDirOff = json.optLong("swapper_pg_dir"),
        kernelBase = json.optLong("kernel_base", DEFAULT_KERNEL_BASE),
        release = json.optString("release").takeIf { it.isNotBlank() },
        verified = json.optBoolean("verified", false),
        source = ConfigSource.fromId(json.optString("source")),
        savedAtMillis = json.optLong("saved_at"),
        raw = json.optString("raw"),
    )

    private fun SavedConfig.sameValuesAs(other: SavedConfig): Boolean =
        initTaskOff == other.initTaskOff &&
            swapperPgDirOff == other.swapperPgDirOff &&
            kernelBase == other.kernelBase &&
            verified == other.verified

    private fun SavedConfig.sameContentAs(other: SavedConfig): Boolean =
        sameValuesAs(other) && raw == other.raw

    private companion object {
        const val KEY_CONFIGS = "configs"
        const val KEY_ACTIVE = "active_config"
        const val KEY_RUN_CONFIG = "run_config"
    }
}

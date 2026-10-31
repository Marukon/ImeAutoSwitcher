package io.github.marukon.imeauto.data

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets

object ConfigManager {
    const val ACTION_SYNC_CONFIG = "io.github.marukon.imeauto.ACTION_SYNC_CONFIG"
    private const val PREFS_NAME = "ime_auto_config"
    const val ACTIVE_FLAG_KEY = "io_github_marukon_imeauto_active"
    const val SETTINGS_CONFIG_KEY = "io_github_marukon_imeauto_config"
    const val SYSTEM_CONFIG_PATH = "/data/system/imeauto_config.json"

    const val KEY_GLOBAL_ENABLED = "global_enabled"
    const val KEY_DEFAULT_IME = "default_ime"
    const val KEY_HIDE_NO_LAUNCHER = "hide_no_launcher"
    const val KEY_AGGRESSIVE_RECHECK = "aggressive_recheck"
    const val KEY_APP_RULES = "app_rules"

    @Volatile
    var isFrameworkServiceConnected: Boolean = false
    var frameworkName: String = ""
    var frameworkVersion: String = ""

    private val json = Json { ignoreUnknownKeys = true }

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Triple-detection mechanism to check module active status:
     * 1. Self hook if app process is scoped
     * 2. Modern libxposed XposedService connection
     * 3. Handshake flag written by system_server hook
     */
    fun isModuleActive(context: Context? = null): Boolean {
        if (isSelfHooked()) {
            return true
        }

        if (isFrameworkServiceConnected) {
            return true
        }

        if (context != null) {
            try {
                val flag = Settings.Global.getString(context.contentResolver, ACTIVE_FLAG_KEY)
                if (flag == "1") {
                    return true
                }
            } catch (t: Throwable) {
                // Ignore
            }
        }
        return false
    }

    /**
     * Hooked at runtime by LSPosed if the module is in scope.
     */
    fun isSelfHooked(): Boolean {
        return false
    }

    fun isGlobalEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GLOBAL_ENABLED, true)
    }

    fun setGlobalEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
        notifyHook(context)
    }

    fun getDefaultIme(context: Context): String {
        return getPrefs(context).getString(KEY_DEFAULT_IME, "") ?: ""
    }

    fun setDefaultIme(context: Context, imeId: String) {
        getPrefs(context).edit().putString(KEY_DEFAULT_IME, imeId).apply()
        notifyHook(context)
    }

    fun isHideNoLauncher(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HIDE_NO_LAUNCHER, true)
    }

    fun setHideNoLauncher(context: Context, hide: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HIDE_NO_LAUNCHER, hide).apply()
    }

    fun isAggressiveRecheck(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AGGRESSIVE_RECHECK, true)
    }

    fun setAggressiveRecheck(context: Context, recheck: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AGGRESSIVE_RECHECK, recheck).apply()
        notifyHook(context)
    }

    fun getAppRules(context: Context): Map<String, String> {
        val rawJson = getPrefs(context).getString(KEY_APP_RULES, null) ?: return emptyMap()
        return try {
            json.decodeFromString<Map<String, String>>(rawJson)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setAppRule(context: Context, packageName: String, imeId: String?) {
        val current = getAppRules(context).toMutableMap()
        if (imeId.isNullOrEmpty()) {
            current.remove(packageName)
        } else {
            current[packageName] = imeId
        }
        val encoded = json.encodeToString(current)
        getPrefs(context).edit().putString(KEY_APP_RULES, encoded).apply()
        notifyHook(context)
    }

    fun buildFullConfigJson(context: Context): String {
        val map = mapOf(
            KEY_GLOBAL_ENABLED to isGlobalEnabled(context).toString(),
            KEY_DEFAULT_IME to getDefaultIme(context),
            KEY_AGGRESSIVE_RECHECK to isAggressiveRecheck(context).toString(),
            KEY_APP_RULES to (getPrefs(context).getString(KEY_APP_RULES, "{}") ?: "{}")
        )
        return json.encodeToString(map)
    }

    /**
     * Dual-channel sync:
     * 1. Direct Settings.Global (zero latency, triggers ContentObserver in system_server)
     * 2. Broadcasts (implicit + explicit to system)
     * 3. Root background fallback (grants WRITE_SECURE_SETTINGS & writes /data/system/imeauto_config.json)
     */
    fun notifyHook(context: Context) {
        val fullJson = buildFullConfigJson(context)

        // 1. Direct Settings.Global write
        var wroteGlobal = false
        try {
            wroteGlobal = Settings.Global.putString(context.contentResolver, SETTINGS_CONFIG_KEY, fullJson)
        } catch (t: Throwable) {
            Log.w("ConfigManager", "Settings.Global direct write deferred: ${t.message}")
        }

        // 2. Broadcasts
        try {
            val intent = Intent(ACTION_SYNC_CONFIG).apply {
                putExtra(KEY_GLOBAL_ENABLED, isGlobalEnabled(context))
                putExtra(KEY_DEFAULT_IME, getDefaultIme(context))
                putExtra(KEY_AGGRESSIVE_RECHECK, isAggressiveRecheck(context))
                putExtra(KEY_APP_RULES, getPrefs(context).getString(KEY_APP_RULES, "{}"))
                putExtra(SETTINGS_CONFIG_KEY, fullJson)
            }
            context.sendBroadcast(intent)

            val systemIntent = Intent(intent).apply {
                setPackage("android")
            }
            context.sendBroadcast(systemIntent)
        } catch (t: Throwable) {
            Log.w("ConfigManager", "Broadcast deferred: ${t.message}")
        }

        // 3. Root background sync (dual assurance)
        Thread {
            try {
                val b64 = Base64.encodeToString(fullJson.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                val proc = Runtime.getRuntime().exec("su")
                proc.outputStream.bufferedWriter().use { writer ->
                    if (!wroteGlobal) {
                        writer.write("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS\n")
                        writer.write("settings put global $SETTINGS_CONFIG_KEY '$fullJson'\n")
                    }
                    writer.write("echo \"$b64\" | base64 -d > $SYSTEM_CONFIG_PATH\n")
                    writer.write("chmod 644 $SYSTEM_CONFIG_PATH\n")
                    writer.write("exit\n")
                    writer.flush()
                }
                proc.waitFor()
            } catch (ignored: Throwable) {}
        }.start()
    }
}

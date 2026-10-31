package io.github.marukon.imeauto.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Base64
import android.util.Log
import io.github.marukon.imeauto.data.model.ImeGroup
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID

object ConfigManager {
    const val ACTION_SYNC_CONFIG = "io.github.marukon.imeauto.ACTION_SYNC_CONFIG"
    private const val PREFS_NAME = "ime_auto_config"
    const val ACTIVE_FLAG_KEY = "io_github_marukon_imeauto_active"
    const val SETTINGS_CONFIG_KEY = "io_github_marukon_imeauto_config"
    const val SYSTEM_CONFIG_PATH = "/data/system/imeauto_config.json"

    const val RULE_KEEP_CURRENT = "__KEEP__"
    const val PREFIX_GROUP = "group:"

    const val KEY_GLOBAL_ENABLED = "global_enabled"
    const val KEY_DEFAULT_IME = "default_ime"
    const val KEY_HIDE_NO_LAUNCHER = "hide_no_launcher"
    const val KEY_AGGRESSIVE_RECHECK = "aggressive_recheck"
    const val KEY_HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val KEY_APP_RULES = "app_rules"
    const val KEY_GROUPS = "groups"
    const val KEY_KNOWN_IME_PACKAGES = "known_ime_packages"

    @Volatile
    var isFrameworkServiceConnected: Boolean = false
    var frameworkName: String = ""
    var frameworkVersion: String = ""

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

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

    fun isLauncherIconHidden(context: Context): Boolean {
        val pm = context.packageManager
        val aliasName = ComponentName(context, "io.github.marukon.imeauto.ui.LauncherAlias")
        return try {
            val state = pm.getComponentEnabledSetting(aliasName)
            state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (t: Throwable) {
            getPrefs(context).getBoolean(KEY_HIDE_LAUNCHER_ICON, false)
        }
    }

    fun setHideLauncherIcon(context: Context, hide: Boolean) {
        val pm = context.packageManager
        val aliasName = ComponentName(context, "io.github.marukon.imeauto.ui.LauncherAlias")
        val state = if (hide) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        try {
            pm.setComponentEnabledSetting(aliasName, state, PackageManager.DONT_KILL_APP)
            getPrefs(context).edit().putBoolean(KEY_HIDE_LAUNCHER_ICON, hide).apply()
        } catch (t: Throwable) {
            Log.e("ConfigManager", "Failed to set launcher alias state: ${t.message}")
        }
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

    fun getGroups(context: Context): List<ImeGroup> {
        val rawJson = getPrefs(context).getString(KEY_GROUPS, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ImeGroup>>(rawJson)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveGroups(context: Context, groups: List<ImeGroup>) {
        val encoded = json.encodeToString(groups)
        getPrefs(context).edit().putString(KEY_GROUPS, encoded).apply()
        notifyHook(context)
    }

    fun addGroup(context: Context, name: String, targetImeId: String): ImeGroup {
        val current = getGroups(context).toMutableList()
        val newGroup = ImeGroup(
            id = UUID.randomUUID().toString().substring(0, 8),
            name = name,
            targetImeId = targetImeId
        )
        current.add(newGroup)
        saveGroups(context, current)
        return newGroup
    }

    fun updateGroup(context: Context, updated: ImeGroup) {
        val current = getGroups(context).map {
            if (it.id == updated.id) updated else it
        }
        saveGroups(context, current)
    }

    fun deleteGroup(context: Context, groupId: String) {
        val currentGroups = getGroups(context).filter { it.id != groupId }
        saveGroups(context, currentGroups)

        // Clean up apps referencing this group
        val currentRules = getAppRules(context).toMutableMap()
        val targetPrefix = "$PREFIX_GROUP$groupId"
        var changed = false
        currentRules.entries.removeIf {
            if (it.value == targetPrefix) {
                changed = true
                true
            } else false
        }
        if (changed) {
            val encoded = json.encodeToString(currentRules)
            getPrefs(context).edit().putString(KEY_APP_RULES, encoded).apply()
            notifyHook(context)
        }
    }

    fun getKnownImePackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_KNOWN_IME_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setKnownImePackages(context: Context, packages: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_KNOWN_IME_PACKAGES, packages).apply()
    }

    fun buildFullConfigJson(context: Context): String {
        val groupsJson = json.encodeToString(getGroups(context))
        val knownImeList = getKnownImePackages(context).toList()
        val knownImeJson = json.encodeToString(knownImeList)

        val map = mapOf(
            KEY_GLOBAL_ENABLED to isGlobalEnabled(context).toString(),
            KEY_DEFAULT_IME to getDefaultIme(context),
            KEY_AGGRESSIVE_RECHECK to isAggressiveRecheck(context).toString(),
            KEY_APP_RULES to (getPrefs(context).getString(KEY_APP_RULES, "{}") ?: "{}"),
            KEY_GROUPS to groupsJson,
            KEY_KNOWN_IME_PACKAGES to knownImeJson
        )
        return json.encodeToString(map)
    }

    fun exportConfigJson(context: Context): String {
        return buildFullConfigJson(context)
    }

    fun importConfigJson(context: Context, configJson: String): Boolean {
        return try {
            val map = json.decodeFromString<Map<String, String>>(configJson)
            val editor = getPrefs(context).edit()

            map[KEY_GLOBAL_ENABLED]?.let { editor.putBoolean(KEY_GLOBAL_ENABLED, it.toBoolean()) }
            map[KEY_DEFAULT_IME]?.let { editor.putString(KEY_DEFAULT_IME, it) }
            map[KEY_AGGRESSIVE_RECHECK]?.let { editor.putBoolean(KEY_AGGRESSIVE_RECHECK, it.toBoolean()) }
            map[KEY_APP_RULES]?.let { editor.putString(KEY_APP_RULES, it) }
            map[KEY_GROUPS]?.let { editor.putString(KEY_GROUPS, it) }
            map[KEY_KNOWN_IME_PACKAGES]?.let {
                val list = json.decodeFromString<List<String>>(it)
                editor.putStringSet(KEY_KNOWN_IME_PACKAGES, list.toSet())
            }

            editor.apply()
            notifyHook(context)
            true
        } catch (t: Throwable) {
            Log.e("ConfigManager", "Failed to import config JSON", t)
            false
        }
    }

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
                putExtra(KEY_GROUPS, json.encodeToString(getGroups(context)))
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
                    writer.write("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS\n")
                    writer.write("settings put global $SETTINGS_CONFIG_KEY \"$(echo '$b64' | base64 -d)\"\n")
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

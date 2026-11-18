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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executors

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

    private val configLock = Any()
    private val rootSyncExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    var isFrameworkServiceConnected: Boolean = false
        set(value) {
            field = value
            frameworkConnectedFlow.value = value
        }
    val frameworkConnectedFlow = MutableStateFlow(false)
    var frameworkName: String = ""
    var frameworkVersion: String = ""

    // Volatile backing field prevents R8 from constant-folding/inlining isSelfHooked() in release builds
    @Volatile
    private var selfHookFallback = false

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
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
                val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                val flag = Settings.Global.getString(context.contentResolver, ACTIVE_FLAG_KEY)
                if (bootCount >= 0 && flag == "boot:$bootCount") {
                    return true
                }
            } catch (t: Throwable) {
                // Ignore
            }
        }
        return false
    }

    fun isSelfHooked(): Boolean {
        return selfHookFallback
    }

    fun isGlobalEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_GLOBAL_ENABLED, true)
    }

    fun setGlobalEnabled(context: Context, enabled: Boolean) = synchronized(configLock) {
        getPrefs(context).edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).apply()
        notifyHook(context)
    }

    fun getDefaultIme(context: Context): String {
        return getPrefs(context).getString(KEY_DEFAULT_IME, "") ?: ""
    }

    fun setDefaultIme(context: Context, imeId: String) = synchronized(configLock) {
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
        return getPrefs(context).getBoolean(KEY_AGGRESSIVE_RECHECK, false)
    }

    fun setAggressiveRecheck(context: Context, recheck: Boolean) = synchronized(configLock) {
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

    fun setAppRule(context: Context, packageName: String, imeId: String?) = synchronized(configLock) {
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

    fun saveGroups(context: Context, groups: List<ImeGroup>) = synchronized(configLock) {
        val encoded = json.encodeToString(groups)
        getPrefs(context).edit().putString(KEY_GROUPS, encoded).apply()
        notifyHook(context)
    }

    fun addGroup(context: Context, name: String, targetImeId: String): ImeGroup = synchronized(configLock) {
        val current = getGroups(context).toMutableList()
        val newGroup = ImeGroup(
            id = UUID.randomUUID().toString().substring(0, 8),
            name = name,
            targetImeId = targetImeId
        )
        current.add(newGroup)
        val encoded = json.encodeToString(current)
        getPrefs(context).edit().putString(KEY_GROUPS, encoded).apply()
        notifyHook(context)
        newGroup
    }

    fun updateGroup(context: Context, updated: ImeGroup) = synchronized(configLock) {
        val current = getGroups(context).map {
            if (it.id == updated.id) updated else it
        }
        val encoded = json.encodeToString(current)
        getPrefs(context).edit().putString(KEY_GROUPS, encoded).apply()
        notifyHook(context)
    }

    fun deleteGroup(context: Context, groupId: String) = synchronized(configLock) {
        val currentGroups = getGroups(context).filter { it.id != groupId }
        val currentRules = getAppRules(context).toMutableMap()
        val targetPrefix = "$PREFIX_GROUP$groupId"
        currentRules.entries.removeIf { it.value == targetPrefix }

        getPrefs(context).edit()
            .putString(KEY_GROUPS, json.encodeToString(currentGroups))
            .putString(KEY_APP_RULES, json.encodeToString(currentRules))
            .apply()
        notifyHook(context)
    }

    fun getKnownImePackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_KNOWN_IME_PACKAGES, emptySet()) ?: emptySet()
    }

    fun setKnownImePackages(context: Context, packages: Set<String>, syncNow: Boolean = true) = synchronized(configLock) {
        val current = getKnownImePackages(context).toSet()
        if (current != packages) {
            getPrefs(context).edit().putStringSet(KEY_KNOWN_IME_PACKAGES, packages.toSet()).apply()
            if (syncNow) {
                notifyHook(context)
            }
        }
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

    fun importConfigJson(context: Context, configJson: String): Boolean = synchronized(configLock) {
        return try {
            val map = json.decodeFromString<Map<String, String>>(configJson)
            val editor = getPrefs(context).edit()

            editor.putBoolean(KEY_GLOBAL_ENABLED, map[KEY_GLOBAL_ENABLED]?.toBoolean() ?: true)
            editor.putString(KEY_DEFAULT_IME, map[KEY_DEFAULT_IME] ?: "")
            editor.putBoolean(KEY_AGGRESSIVE_RECHECK, map[KEY_AGGRESSIVE_RECHECK]?.toBoolean() ?: false)

            val groupsList = runCatching {
                map[KEY_GROUPS]?.let { json.decodeFromString<List<ImeGroup>>(it) }
            }.getOrNull() ?: emptyList()

            val rulesMap = runCatching {
                map[KEY_APP_RULES]?.let { json.decodeFromString<Map<String, String>>(it) }
            }.getOrNull() ?: emptyMap()

            val groupIds = groupsList.map { it.id }.toSet()
            val cleanedRules = rulesMap.filterValues { rule ->
                if (rule.startsWith(PREFIX_GROUP)) {
                    val gId = rule.removePrefix(PREFIX_GROUP)
                    groupIds.contains(gId)
                } else {
                    true
                }
            }
            editor.putString(KEY_APP_RULES, json.encodeToString(cleanedRules))
            editor.putString(KEY_GROUPS, json.encodeToString(groupsList))

            map[KEY_KNOWN_IME_PACKAGES]?.let { rawKnown ->
                runCatching {
                    json.decodeFromString<List<String>>(rawKnown).toSet()
                }.getOrNull()?.let { knownSet ->
                    editor.putStringSet(KEY_KNOWN_IME_PACKAGES, knownSet)
                }
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
        val appContext = context.applicationContext ?: context
        val fullJson: String
        val globalEnabled: Boolean
        val defaultIme: String
        val aggressiveRecheck: Boolean
        val appRulesJson: String
        val groupsJson: String

        synchronized(configLock) {
            fullJson = buildFullConfigJson(appContext)
            globalEnabled = isGlobalEnabled(appContext)
            defaultIme = getDefaultIme(appContext)
            aggressiveRecheck = isAggressiveRecheck(appContext)
            appRulesJson = getPrefs(appContext).getString(KEY_APP_RULES, "{}") ?: "{}"
            groupsJson = json.encodeToString(getGroups(appContext))

            // Queue root sync inside configLock so sequential mutations never reorder writes
            rootSyncExecutor.execute {
                var wroteGlobal = false
                try {
                    wroteGlobal = Settings.Global.putString(appContext.contentResolver, SETTINGS_CONFIG_KEY, fullJson)
                } catch (t: Throwable) {
                    Log.w("ConfigManager", "Settings.Global direct write deferred: ${t.message}")
                }

                // Targeted system broadcast
                try {
                    val systemIntent = Intent(ACTION_SYNC_CONFIG).apply {
                        setPackage("android")
                        putExtra(KEY_GLOBAL_ENABLED, globalEnabled)
                        putExtra(KEY_DEFAULT_IME, defaultIme)
                        putExtra(KEY_AGGRESSIVE_RECHECK, aggressiveRecheck)
                        putExtra(KEY_APP_RULES, appRulesJson)
                        putExtra(KEY_GROUPS, groupsJson)
                        putExtra(SETTINGS_CONFIG_KEY, fullJson)
                    }
                    appContext.sendBroadcast(systemIntent)
                } catch (t: Throwable) {
                    Log.w("ConfigManager", "Broadcast deferred: ${t.message}")
                }

                var proc: Process? = null
                try {
                    val b64 = Base64.encodeToString(fullJson.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
                    proc = Runtime.getRuntime().exec("su")
                    proc.outputStream.bufferedWriter().use { writer ->
                        if (!wroteGlobal) {
                            writer.write("pm grant ${appContext.packageName} android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1\n")
                        }
                        writer.write("echo '$b64' | base64 -d > $SYSTEM_CONFIG_PATH.tmp && mv -f $SYSTEM_CONFIG_PATH.tmp $SYSTEM_CONFIG_PATH\n")
                        writer.write("chmod 644 $SYSTEM_CONFIG_PATH >/dev/null 2>&1\n")
                        writer.write("restorecon $SYSTEM_CONFIG_PATH >/dev/null 2>&1 || true\n")
                        writer.write("exit\n")
                        writer.flush()
                    }
                    val finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    if (!finished) {
                        proc.destroyForcibly()
                    }
                } catch (ignored: Throwable) {
                    proc?.destroyForcibly()
                } finally {
                    try {
                        proc?.inputStream?.close()
                        proc?.errorStream?.close()
                    } catch (ignored: Throwable) {}
                }

                if (!wroteGlobal) {
                    try {
                        Settings.Global.putString(appContext.contentResolver, SETTINGS_CONFIG_KEY, fullJson)
                    } catch (t: Throwable) {
                        Log.w("ConfigManager", "Settings.Global retry failed: ${t.message}")
                    }
                }
            }
        }
    }
}

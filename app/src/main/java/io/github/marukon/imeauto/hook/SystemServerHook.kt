package io.github.marukon.imeauto.hook

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import io.github.marukon.imeauto.data.ConfigManager
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

object SystemServerHook {

    private const val ACTIVE_FLAG_KEY = "io_github_marukon_imeauto_active"

    @Volatile
    private var module: XposedModule? = null
    @Volatile
    private var systemContext: Context? = null
    @Volatile
    private var immsInstance: Any? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val switchExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var isForegroundHookInstalled = false

    @Volatile
    private var isImmsHookInstalled = false

    @Volatile
    private var isUnbindHookInstalled = false

    @Volatile
    private var isContextInitialized = false

    @Volatile
    private var isReceiverRegistered = false

    @Volatile
    private var isSettingsObserverRegistered = false

    @Volatile
    private var isConfigLoaded = false

    // In-memory configuration cache
    @Volatile
    private var isGlobalEnabled = true

    @Volatile
    private var defaultImeId = ""

    @Volatile
    private var aggressiveRecheck = true

    private val appRules = ConcurrentHashMap<String, String>()
    private val groupsMap = ConcurrentHashMap<String, String>()
    private val knownImePackages = ConcurrentHashMap.newKeySet<String>()
    private val nonImePackages = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var lastTargetIme: String? = null

    @Volatile
    private var isCustomImeActive = false

    @Volatile
    private var pendingVerificationTask: java.util.concurrent.ScheduledFuture<*>? = null

    @Synchronized
    fun init(xposedModule: XposedModule, classLoader: ClassLoader) {
        module = xposedModule

        // 1. Try resolving system context if not already done
        if (!isContextInitialized) {
            val ctx = HookUtils.getSystemContext()
            if (ctx != null) {
                initSystemContext(ctx)
            }
        }

        // 2. Install foreground activity monitoring hooks
        if (!isForegroundHookInstalled) {
            hookActivityTaskManagerService(classLoader)
            hookTaskAndActivityRecord(classLoader)
        }

        // 3. Install IMMS hooks
        if (!isImmsHookInstalled) {
            hookInputMethodManagerService(classLoader)
        }

        // 4. Install defensive crash guard for ContextImpl.unbindService (ColorOS 16 / Android 16 safety)
        hookContextImplUnbindService(classLoader)
    }

    private fun hookContextImplUnbindService(classLoader: ClassLoader) {
        if (isUnbindHookInstalled) return
        try {
            val contextImplClass = Class.forName("android.app.ContextImpl", false, classLoader)
            val unbindMethod = contextImplClass.getDeclaredMethod(
                "unbindService",
                android.content.ServiceConnection::class.java
            )
            module?.hook(unbindMethod)
                ?.setPriority(XposedInterface.PRIORITY_HIGHEST)
                ?.intercept { chain ->
                    try {
                        chain.proceed()
                    } catch (e: IllegalArgumentException) {
                        if (e.message?.contains("Service not registered") == true) {
                            HookUtils.log("Suppressed unbindService exception to prevent system_server crash: ${e.message}")
                            null
                        } else {
                            throw e
                        }
                    }
                }
            isUnbindHookInstalled = true
            HookUtils.log("Successfully installed crash-prevention guard for ContextImpl.unbindService")
        } catch (t: Throwable) {
            HookUtils.logE("Failed to install unbindService crash guard", t)
        }
    }

    private fun hookActivityTaskManagerService(classLoader: ClassLoader) {
        try {
            val atmsClass = try {
                Class.forName("com.android.server.wm.ActivityTaskManagerService", false, classLoader)
            } catch (e: ClassNotFoundException) {
                Class.forName("com.android.server.am.ActivityManagerService", false, classLoader)
            }

            // Hook onSystemReady to capture system Context and initialize config
            val onSystemReadyMethod = HookUtils.findFirstMethodNamed(atmsClass, "onSystemReady")
            if (onSystemReadyMethod != null) {
                module?.hook(onSystemReadyMethod)
                    ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                    ?.intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val thisObj = chain.thisObject
                            val contextField = thisObj.javaClass.getDeclaredField("mContext")
                            contextField.isAccessible = true
                            val ctx = contextField.get(thisObj) as? Context
                            if (ctx != null) {
                                initSystemContext(ctx)
                            }
                            mainHandler.post { ensureConfigAndReceiver() }
                        } catch (t: Throwable) {
                            HookUtils.logE("Failed in onSystemReady intercept", t)
                        }
                        result
                    }
            }

            // Legacy Android 9/10 hook
            val setResumedMethod = HookUtils.findFirstMethodNamed(
                atmsClass,
                "setResumedActivityUncheckLocked"
            )
            if (setResumedMethod != null) {
                HookUtils.log("Found setResumedActivityUncheckLocked on ATMS, installing hook...")
                module?.hook(setResumedMethod)
                    ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                    ?.intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val args = chain.args
                            if (args.isNotEmpty()) {
                                val activityRecord = args[0]
                                val pkg = HookUtils.getPackageNameFromActivityRecord(activityRecord)
                                if (!pkg.isNullOrEmpty()) {
                                    handleForegroundAppChanged(pkg)
                                }
                            }
                        } catch (t: Throwable) {
                            HookUtils.logE("Error in ATMS setResumedActivity intercept", t)
                        }
                        result
                    }
                isForegroundHookInstalled = true
            }
        } catch (t: Throwable) {
            HookUtils.logE("Error hooking ActivityTaskManagerService", t)
        }
    }

    private fun hookTaskAndActivityRecord(classLoader: ClassLoader) {
        // Modern Android 13-16: TaskFragment.setResumedActivity(ActivityRecord, String)
        // Fallback Android 11-12: Task.setResumedActivity(ActivityRecord, String)
        val taskClassesToTry = listOf("com.android.server.wm.TaskFragment", "com.android.server.wm.Task")
        for (className in taskClassesToTry) {
            try {
                val cls = Class.forName(className, false, classLoader)
                val setResumedMethod = HookUtils.findFirstMethodNamed(cls, "setResumedActivity")
                if (setResumedMethod != null) {
                    HookUtils.log("Found $className.setResumedActivity, installing foreground hook...")
                    module?.hook(setResumedMethod)
                        ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                        ?.intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val args = chain.args
                                if (args.isNotEmpty()) {
                                    val activityRecord = args[0]
                                    val pkg = HookUtils.getPackageNameFromActivityRecord(activityRecord)
                                    if (!pkg.isNullOrEmpty()) {
                                        handleForegroundAppChanged(pkg)
                                    }
                                }
                            } catch (t: Throwable) {
                                HookUtils.logE("Error in $className.setResumedActivity intercept", t)
                            }
                            result
                        }
                    isForegroundHookInstalled = true
                    break
                }
            } catch (t: Throwable) {
                HookUtils.log("$className.setResumedActivity hook error: ${t.message}")
            }
        }

        // Secondary fallback: ActivityRecord.onTopResumedActivityChanged(boolean)
        try {
            val recordClass = Class.forName("com.android.server.wm.ActivityRecord", false, classLoader)
            val onTopMethod = HookUtils.findFirstMethodNamed(recordClass, "onTopResumedActivityChanged")
            if (onTopMethod != null) {
                HookUtils.log("Found ActivityRecord.onTopResumedActivityChanged, installing hook...")
                module?.hook(onTopMethod)
                    ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                    ?.intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val args = chain.args
                            val isTop = (args.firstOrNull() as? Boolean) ?: true
                            if (isTop) {
                                val pkg = HookUtils.getPackageNameFromActivityRecord(chain.thisObject)
                                if (!pkg.isNullOrEmpty()) {
                                    handleForegroundAppChanged(pkg)
                                }
                            }
                        } catch (t: Throwable) {
                            HookUtils.logE("Error in onTopResumedActivityChanged intercept", t)
                        }
                        result
                    }
                isForegroundHookInstalled = true
            }
        } catch (t: Throwable) {
            HookUtils.log("ActivityRecord.onTopResumedActivityChanged hook error: ${t.message}")
        }
    }

    private fun hookInputMethodManagerService(classLoader: ClassLoader) {
        try {
            val immsClass = Class.forName(
                "com.android.server.inputmethod.InputMethodManagerService",
                false,
                classLoader
            )

            // Capture IMMS instance and Context from constructors
            for (constructor in immsClass.declaredConstructors) {
                module?.hook(constructor)
                    ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                    ?.intercept { chain ->
                        val result = chain.proceed()
                        immsInstance = chain.thisObject
                        HookUtils.log("Captured InputMethodManagerService instance")

                        if (!isContextInitialized) {
                            for (arg in chain.args) {
                                if (arg is Context) {
                                    initSystemContext(arg)
                                    break
                                }
                            }
                            if (!isContextInitialized && immsInstance != null) {
                                try {
                                    val ctxField = immsInstance!!.javaClass.getDeclaredField("mContext")
                                    ctxField.isAccessible = true
                                    val ctx = ctxField.get(immsInstance) as? Context
                                    if (ctx != null) initSystemContext(ctx)
                                } catch (ignored: Throwable) {}
                            }
                        }
                        result
                    }
            }

            // Hook startInputOrWindowGainedFocus on IMMS
            hookInputFocusMethods(immsClass, "startInputOrWindowGainedFocus")
            hookInputFocusMethods(immsClass, "startInputOrWindowGainedFocusAsync")
            isImmsHookInstalled = true
        } catch (t: Throwable) {
            HookUtils.logE("Error hooking InputMethodManagerService", t)
        }

        // Modern Android 15/16: Hook IInputMethodManagerImpl
        try {
            val implClass = Class.forName(
                "com.android.server.inputmethod.IInputMethodManagerImpl",
                false,
                classLoader
            )
            hookInputFocusMethods(implClass, "startInputOrWindowGainedFocus")
            hookInputFocusMethods(implClass, "startInputOrWindowGainedFocusAsync")
        } catch (t: Throwable) {
            HookUtils.log("IInputMethodManagerImpl hook fallback: ${t.message}")
        }
    }

    private fun hookInputFocusMethods(targetClass: Class<*>, methodName: String) {
        val methods = targetClass.declaredMethods.filter { it.name == methodName }
        for (m in methods) {
            try {
                HookUtils.log("Hooking ${targetClass.name}.${m.name}...")
                module?.hook(m)
                    ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                    ?.intercept { chain ->
                        try {
                            if (isGlobalEnabled && aggressiveRecheck && isCustomImeActive) {
                                val pkg = currentForegroundPackage
                                if (!pkg.isNullOrEmpty()) {
                                    switchExecutor.execute {
                                        try {
                                            val resolution = resolveAppRule(pkg)
                                            if (resolution is RuleResolution.TargetCustom) {
                                                verifyAndEnforceIme(resolution.imeId, "${targetClass.simpleName}.${m.name}")
                                            }
                                        } catch (t: Throwable) {
                                            HookUtils.logE("Recheck error in ${targetClass.simpleName}.${m.name}", t)
                                        }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            HookUtils.logE("Error in ${targetClass.simpleName}.${m.name} intercept", t)
                        }
                        chain.proceed()
                    }
            } catch (t: Throwable) {
                HookUtils.logE("Failed to hook ${targetClass.name}.${m.name}", t)
            }
        }
    }

    @Synchronized
    private fun initSystemContext(context: Context) {
        if (isContextInitialized) return
        systemContext = context
        isContextInitialized = true
        HookUtils.log("System context registered, setting up active handshake flag...")

        // Mark active state in Settings.Global so UI app can verify system_server hook is alive
        try {
            HookUtils.putGlobalString(context.contentResolver, ACTIVE_FLAG_KEY, "1")
            HookUtils.log("Marked $ACTIVE_FLAG_KEY in Settings.Global")
        } catch (t: Throwable) {
            HookUtils.logE("Failed to write active state to Settings.Global", t)
        }

        // Post retries to allow AMS and PMS to finish starting
        mainHandler.postDelayed({ ensureConfigAndReceiver() }, 3000)
        mainHandler.postDelayed({ ensureConfigAndReceiver() }, 8000)
    }

    @Synchronized
    private fun ensureConfigAndReceiver() {
        val ctx = systemContext ?: HookUtils.getSystemContext()?.also { systemContext = it } ?: return

        if (!isConfigLoaded || appRules.isEmpty()) {
            loadConfigFromAllSources(ctx)
        }

        if (!isSettingsObserverRegistered) {
            registerSettingsObserver(ctx)
        }

        if (!isReceiverRegistered) {
            registerConfigReceiver(ctx)
        }
    }

    private fun registerSettingsObserver(context: Context) {
        if (isSettingsObserverRegistered) return
        try {
            val uri = Settings.Global.getUriFor(ConfigManager.SETTINGS_CONFIG_KEY)
            context.contentResolver.registerContentObserver(
                uri,
                false,
                object : android.database.ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        switchExecutor.execute {
                            HookUtils.log("Settings.Global changed, reloading configuration...")
                            loadConfigFromAllSources(context)
                        }
                    }
                }
            )
            isSettingsObserverRegistered = true
            HookUtils.log("Registered SettingsObserver for ${ConfigManager.SETTINGS_CONFIG_KEY}")
        } catch (t: Throwable) {
            HookUtils.log("registerSettingsObserver deferred: ${t.message}")
        }
    }

    private fun registerConfigReceiver(context: Context) {
        try {
            val filter = IntentFilter(ConfigManager.ACTION_SYNC_CONFIG)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent == null) return
                    try {
                        applyConfigFromIntent(intent, context)
                    } catch (t: Throwable) {
                        HookUtils.logE("Error processing ACTION_SYNC_CONFIG intent", t)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            isReceiverRegistered = true
            HookUtils.log("Registered ACTION_SYNC_CONFIG receiver in system_server")
        } catch (t: Throwable) {
            HookUtils.log("registerConfigReceiver deferred: ${t.message}")
        }
    }

    fun isValidImeId(id: String?): Boolean {
        if (id.isNullOrEmpty()) return false
        if (id == ConfigManager.RULE_KEEP_CURRENT) return false
        if (id.startsWith(ConfigManager.PREFIX_GROUP)) return false
        val slashIndex = id.indexOf('/')
        return slashIndex > 0 && slashIndex < id.length - 1
    }

    private fun parseGroupsJson(groupsRaw: Any) {
        try {
            val groupsArr = when (groupsRaw) {
                is org.json.JSONArray -> groupsRaw
                is String -> org.json.JSONArray(groupsRaw)
                else -> null
            }
            if (groupsArr != null) {
                groupsMap.clear()
                for (i in 0 until groupsArr.length()) {
                    val item = groupsArr.getJSONObject(i)
                    val id = item.optString("id")
                    val ime = item.optString("targetImeId")
                    if (id.isNotEmpty()) {
                        groupsMap[id] = ime
                    }
                }
                HookUtils.log("Parsed ${groupsMap.size} groups into groupsMap")
            }
        } catch (e: Throwable) {
            HookUtils.logE("Failed to parse groups JSON", e)
        }
    }

    private fun loadConfigFromAllSources(context: Context) {
        // Priority 1: Settings.Global (zero IPC, completely internal to system_server)
        try {
            val globalConfig = Settings.Global.getString(context.contentResolver, ConfigManager.SETTINGS_CONFIG_KEY)
            if (!globalConfig.isNullOrEmpty()) {
                applyConfigFromJsonString(globalConfig, "Settings.Global")
            }
        } catch (t: Throwable) {
            HookUtils.log("Settings.Global read error: ${t.message}")
        }

        // Priority 2: Direct file from /data/system/imeauto_config.json
        try {
            val file = java.io.File(ConfigManager.SYSTEM_CONFIG_PATH)
            if (file.exists() && file.canRead()) {
                val content = file.readText()
                if (content.isNotEmpty()) {
                    applyConfigFromJsonString(content, "File(/data/system)")
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("File read error: ${t.message}")
        }

        // Priority 3: Fallback ContentProvider (always load if groupsMap is empty or appRules is empty)
        if (groupsMap.isEmpty() || appRules.isEmpty()) {
            loadConfigFromProvider(context)
        }

        // Priority 4: If defaultImeId is still empty, auto-cache current system default
        if (defaultImeId.isEmpty()) {
            try {
                val currentSysIme = HookUtils.getSecureStringForUser(
                    context.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    HookUtils.getCurrentUserId()
                )
                if (!currentSysIme.isNullOrEmpty() && isValidImeId(currentSysIme)) {
                    defaultImeId = currentSysIme
                    HookUtils.log("Auto-cached current system IME as fallback default: $defaultImeId")
                }
            } catch (ignored: Throwable) {}
        }
    }

    private fun applyConfigFromJsonString(jsonStr: String, source: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            if (root.has(ConfigManager.KEY_GLOBAL_ENABLED)) {
                isGlobalEnabled = root.optBoolean(ConfigManager.KEY_GLOBAL_ENABLED, true)
            }
            if (root.has(ConfigManager.KEY_DEFAULT_IME)) {
                defaultImeId = root.optString(ConfigManager.KEY_DEFAULT_IME, "")
            }
            if (root.has(ConfigManager.KEY_AGGRESSIVE_RECHECK)) {
                aggressiveRecheck = root.optBoolean(ConfigManager.KEY_AGGRESSIVE_RECHECK, true)
            }
            if (root.has(ConfigManager.KEY_APP_RULES)) {
                val rulesRaw = root.get(ConfigManager.KEY_APP_RULES)
                val rulesObj = when (rulesRaw) {
                    is JSONObject -> rulesRaw
                    is String -> JSONObject(rulesRaw)
                    else -> null
                }
                if (rulesObj != null) {
                    appRules.clear()
                    for (key in rulesObj.keys()) {
                        appRules[key] = rulesObj.getString(key)
                    }
                }
            }
            if (root.has(ConfigManager.KEY_GROUPS)) {
                parseGroupsJson(root.get(ConfigManager.KEY_GROUPS))
            }
            if (root.has(ConfigManager.KEY_KNOWN_IME_PACKAGES)) {
                val imeRaw = root.get(ConfigManager.KEY_KNOWN_IME_PACKAGES)
                val imeArr = when (imeRaw) {
                    is org.json.JSONArray -> imeRaw
                    is String -> org.json.JSONArray(imeRaw)
                    else -> null
                }
                if (imeArr != null) {
                    for (i in 0 until imeArr.length()) {
                        knownImePackages.add(imeArr.getString(i))
                    }
                }
            }
            isConfigLoaded = true
            HookUtils.log("Config successfully applied from [$source]: ${appRules.size} rules, ${groupsMap.size} groups, default=$defaultImeId, enabled=$isGlobalEnabled")
            currentForegroundPackage?.let { handleForegroundAppChanged(it, force = true) }
            true
        } catch (e: Throwable) {
            HookUtils.logE("Failed to parse config from [$source]", e)
            false
        }
    }

    private fun loadConfigFromProvider(context: Context) {
        try {
            val uri = Uri.parse("content://io.github.marukon.imeauto.provider")
            val bundle = context.contentResolver.call(uri, "getConfig", null, null)
            if (bundle != null) {
                isGlobalEnabled = bundle.getBoolean(ConfigManager.KEY_GLOBAL_ENABLED, true)
                defaultImeId = bundle.getString(ConfigManager.KEY_DEFAULT_IME, "") ?: ""
                aggressiveRecheck = bundle.getBoolean(ConfigManager.KEY_AGGRESSIVE_RECHECK, true)
                val rulesJson = bundle.getString(ConfigManager.KEY_APP_RULES)
                if (!rulesJson.isNullOrEmpty()) {
                    val obj = JSONObject(rulesJson)
                    appRules.clear()
                    for (key in obj.keys()) {
                        appRules[key] = obj.getString(key)
                    }
                }
                val groupsJson = bundle.getString(ConfigManager.KEY_GROUPS)
                if (!groupsJson.isNullOrEmpty()) {
                    parseGroupsJson(groupsJson)
                }
                val fullJson = bundle.getString(ConfigManager.SETTINGS_CONFIG_KEY)
                if (!fullJson.isNullOrEmpty()) {
                    applyConfigFromJsonString(fullJson, "Provider.fullJson")
                }
                isConfigLoaded = true
                HookUtils.log("Loaded config via provider: ${appRules.size} rules, ${groupsMap.size} groups, default=$defaultImeId, enabled=$isGlobalEnabled")
            }
        } catch (t: Throwable) {
            HookUtils.log("loadConfigFromProvider deferred: ${t.message}")
        }
    }

    private fun applyConfigFromIntent(intent: Intent, context: Context) {
        HookUtils.putGlobalString(context.contentResolver, ACTIVE_FLAG_KEY, "1")

        val fullJson = intent.getStringExtra(ConfigManager.SETTINGS_CONFIG_KEY)
        if (!fullJson.isNullOrEmpty() && applyConfigFromJsonString(fullJson, "Broadcast.fullJson")) {
            return
        }

        isGlobalEnabled = intent.getBooleanExtra(ConfigManager.KEY_GLOBAL_ENABLED, true)
        defaultImeId = intent.getStringExtra(ConfigManager.KEY_DEFAULT_IME) ?: ""
        aggressiveRecheck = intent.getBooleanExtra(ConfigManager.KEY_AGGRESSIVE_RECHECK, true)

        val rulesJson = intent.getStringExtra(ConfigManager.KEY_APP_RULES)
        if (!rulesJson.isNullOrEmpty()) {
            try {
                val obj = JSONObject(rulesJson)
                appRules.clear()
                for (key in obj.keys()) {
                    appRules[key] = obj.getString(key)
                }
                isConfigLoaded = true
            } catch (e: Exception) {
                HookUtils.logE("Failed to parse app rules JSON", e)
            }
        }

        val groupsJson = intent.getStringExtra(ConfigManager.KEY_GROUPS)
        if (!groupsJson.isNullOrEmpty()) {
            parseGroupsJson(groupsJson)
        }

        HookUtils.log("Config updated via broadcast: ${appRules.size} rules, ${groupsMap.size} groups, default=$defaultImeId, enabled=$isGlobalEnabled")
        currentForegroundPackage?.let { handleForegroundAppChanged(it, force = true) }
    }

    private sealed interface RuleResolution {
        data class TargetCustom(val imeId: String) : RuleResolution
        object KeepCurrent : RuleResolution
        object Unmodified : RuleResolution
    }

    private fun handleForegroundAppChanged(packageName: String, force: Boolean = false) {
        if (!force && packageName == currentForegroundPackage) {
            return
        }
        currentForegroundPackage = packageName

        switchExecutor.execute {
            try {
                if (!isConfigLoaded || !isReceiverRegistered) {
                    ensureConfigAndReceiver()
                }

                if (!isGlobalEnabled) {
                    return@execute
                }

                when (val resolution = resolveAppRule(packageName)) {
                    is RuleResolution.TargetCustom -> {
                        val customIme = resolution.imeId
                        val isDifferentFromDefault = isValidImeId(defaultImeId) && customIme != defaultImeId
                        HookUtils.log("Foreground [$packageName] has custom rule: $customIme (differentFromDefault=$isDifferentFromDefault)")
                        applyImeSwitch(customIme, packageName)
                        isCustomImeActive = isDifferentFromDefault
                    }
                    is RuleResolution.KeepCurrent -> {
                        // User explicitly requested to keep current IME (or unconfigured IME app).
                        // Strictly leave IME untouched, and preserve isCustomImeActive!
                        HookUtils.log("Foreground [$packageName] is KeepCurrent, leaving IME unchanged (isCustomImeActive=$isCustomImeActive)")
                    }
                    is RuleResolution.Unmodified -> {
                        if (isCustomImeActive) {
                            // User exited a modified app whose IME differed from default: perform a single fallback
                            if (isValidImeId(defaultImeId)) {
                                HookUtils.log("Exited modified app, restoring default IME [$defaultImeId] for [$packageName]")
                                applyImeSwitch(defaultImeId, "RestoreAfter:$packageName")
                            }
                            isCustomImeActive = false
                        } else {
                            // System is in normal state and app has no custom rule: zero injection!
                            HookUtils.log("Foreground [$packageName] is unmodified, skipping injection")
                        }
                    }
                }
            } catch (t: Throwable) {
                HookUtils.logE("Error handling foreground app change for $packageName", t)
            }
        }
    }

    private fun resolveAppRule(pkg: String?): RuleResolution {
        if (pkg.isNullOrEmpty()) return RuleResolution.Unmodified
        val mapped = appRules[pkg]
        if (!mapped.isNullOrEmpty()) {
            if (mapped == ConfigManager.RULE_KEEP_CURRENT) {
                return RuleResolution.KeepCurrent
            }
            if (mapped.startsWith(ConfigManager.PREFIX_GROUP)) {
                val groupId = mapped.removePrefix(ConfigManager.PREFIX_GROUP)
                val groupIme = groupsMap[groupId]
                if (groupIme == ConfigManager.RULE_KEEP_CURRENT) {
                    return RuleResolution.KeepCurrent
                }
                if (!groupIme.isNullOrEmpty() && isValidImeId(groupIme)) {
                    return RuleResolution.TargetCustom(groupIme)
                }
                return RuleResolution.Unmodified
            }
            if (isValidImeId(mapped)) {
                return RuleResolution.TargetCustom(mapped)
            }
            return RuleResolution.Unmodified
        }

        // Unconfigured apps: IME apps default to KeepCurrent
        if (isImePackage(pkg)) {
            return RuleResolution.KeepCurrent
        }

        return RuleResolution.Unmodified
    }

    private fun isImePackage(pkg: String): Boolean {
        if (knownImePackages.contains(pkg)) return true
        if (nonImePackages.contains(pkg)) return false
        val ctx = systemContext ?: HookUtils.getSystemContext() ?: return false
        return try {
            val intent = Intent("android.view.InputMethod").setPackage(pkg)
            val services = ctx.packageManager.queryIntentServices(intent, android.content.pm.PackageManager.MATCH_ALL)
            if (services.isNotEmpty()) {
                knownImePackages.add(pkg)
                true
            } else {
                nonImePackages.add(pkg)
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun applyImeSwitch(targetIme: String, triggerPkg: String) {
        if (!isValidImeId(targetIme)) {
            HookUtils.log("Refusing to apply invalid IME ID: '$targetIme' for [$triggerPkg]")
            return
        }

        val ctx = systemContext ?: HookUtils.getSystemContext()?.also { systemContext = it } ?: return
        val currentUserId = HookUtils.getCurrentUserId()

        val token = android.os.Binder.clearCallingIdentity()
        try {
            val currentIme = HookUtils.getSecureStringForUser(
                ctx.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                currentUserId
            )

            // Self-healing check: if system settings currently has an invalid/corrupted IME (e.g. group:xxx),
            // force write the valid targetIme to restore keyboard functionality immediately.
            val isCurrentCorrupted = !isValidImeId(currentIme)

            if (!isCurrentCorrupted && targetIme == currentIme) {
                lastTargetIme = targetIme
                return
            }

            HookUtils.log("Switching IME for [$triggerPkg]: current=$currentIme -> target=$targetIme")

            // 1. Write to Settings.Secure with system privileges (triggers official system_server IME event dispatch)
            HookUtils.putSecureStringForUser(
                ctx.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                targetIme,
                currentUserId
            )
            lastTargetIme = targetIme
        } catch (t: Throwable) {
            HookUtils.logE("Failed to switch IME in Settings.Secure", t)
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }

        // 2. Post-Switch Delayed Recheck asynchronously via switchExecutor with debouncing
        if (aggressiveRecheck) {
            pendingVerificationTask?.cancel(false)
            pendingVerificationTask = switchExecutor.schedule({
                verifyAndEnforceIme(targetIme, "DelayedVerification")
            }, 250, TimeUnit.MILLISECONDS)
        }
    }

    private fun verifyAndEnforceIme(expectedIme: String, source: String) {
        if (!isValidImeId(expectedIme)) return
        if (expectedIme != lastTargetIme) {
            HookUtils.log("Stale recheck ignored for $expectedIme (current target is $lastTargetIme)")
            return
        }
        val ctx = systemContext ?: HookUtils.getSystemContext()?.also { systemContext = it } ?: return
        val currentUserId = HookUtils.getCurrentUserId()

        val token = android.os.Binder.clearCallingIdentity()
        try {
            val current = HookUtils.getSecureStringForUser(
                ctx.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                currentUserId
            )

            if (current != expectedIme) {
                HookUtils.log("Recheck triggered by [$source]: mismatch ($current != $expectedIme), enforcing...")
                HookUtils.putSecureStringForUser(
                    ctx.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    expectedIme,
                    currentUserId
                )
            }
        } catch (t: Throwable) {
            HookUtils.logE("Failed to enforce IME in recheck", t)
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }
}

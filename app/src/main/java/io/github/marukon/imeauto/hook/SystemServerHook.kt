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

    private var module: XposedModule? = null
    private var systemContext: Context? = null
    private var immsInstance: Any? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val switchExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    @Volatile
    private var isForegroundHookInstalled = false

    @Volatile
    private var isImmsHookInstalled = false

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

    @Volatile
    private var currentForegroundPackage: String? = null

    @Volatile
    private var lastTargetIme: String? = null

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
                        } catch (t: Throwable) {
                            HookUtils.logE("Failed to capture Context from onSystemReady", t)
                        }
                        ensureConfigAndReceiver()
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
                        val args = chain.args
                        if (args.isNotEmpty()) {
                            val activityRecord = args[0]
                            val pkg = HookUtils.getPackageNameFromActivityRecord(activityRecord)
                            if (!pkg.isNullOrEmpty()) {
                                handleForegroundAppChanged(pkg)
                            }
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
                            val args = chain.args
                            if (args.isNotEmpty()) {
                                val activityRecord = args[0]
                                val pkg = HookUtils.getPackageNameFromActivityRecord(activityRecord)
                                if (!pkg.isNullOrEmpty()) {
                                    handleForegroundAppChanged(pkg)
                                }
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
                        val args = chain.args
                        val isTop = (args.firstOrNull() as? Boolean) ?: true
                        if (isTop) {
                            val pkg = HookUtils.getPackageNameFromActivityRecord(chain.thisObject)
                            if (!pkg.isNullOrEmpty()) {
                                handleForegroundAppChanged(pkg)
                            }
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
                        var inputPkg: String? = null
                        for (arg in chain.args) {
                            if (arg is EditorInfo) {
                                inputPkg = arg.packageName
                                break
                            }
                        }

                        if (!inputPkg.isNullOrEmpty()) {
                            handleForegroundAppChanged(inputPkg)
                        } else if (isGlobalEnabled && aggressiveRecheck) {
                            val pkg = currentForegroundPackage
                            switchExecutor.execute {
                                val expectedIme = getTargetImeForPackage(pkg)
                                if (!expectedIme.isNullOrEmpty()) {
                                    verifyAndEnforceIme(expectedIme, "${targetClass.simpleName}.${m.name}")
                                }
                            }
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
                        HookUtils.log("Settings.Global changed, reloading configuration...")
                        loadConfigFromAllSources(context)
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
                    applyConfigFromIntent(intent, context)
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

    private fun loadConfigFromAllSources(context: Context) {
        // Priority 1: Settings.Global (zero IPC, completely internal to system_server)
        try {
            val globalConfig = Settings.Global.getString(context.contentResolver, ConfigManager.SETTINGS_CONFIG_KEY)
            if (!globalConfig.isNullOrEmpty()) {
                if (applyConfigFromJsonString(globalConfig, "Settings.Global")) {
                    return
                }
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
                    if (applyConfigFromJsonString(content, "File(/data/system)")) {
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("File read error: ${t.message}")
        }

        // Priority 3: Fallback ContentProvider
        loadConfigFromProvider(context)
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
                val groupsRaw = root.get(ConfigManager.KEY_GROUPS)
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
                }
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
            HookUtils.log("Config successfully applied from [$source]: ${appRules.size} rules, default=$defaultImeId, enabled=$isGlobalEnabled")
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
                isConfigLoaded = true
                HookUtils.log("Loaded config via provider: ${appRules.size} rules, default=$defaultImeId, enabled=$isGlobalEnabled")
            }
        } catch (t: Throwable) {
            HookUtils.log("loadConfigFromProvider deferred: ${t.message}")
        }
    }

    private fun applyConfigFromIntent(intent: Intent, context: Context) {
        isGlobalEnabled = intent.getBooleanExtra(ConfigManager.KEY_GLOBAL_ENABLED, true)
        defaultImeId = intent.getStringExtra(ConfigManager.KEY_DEFAULT_IME) ?: ""
        aggressiveRecheck = intent.getBooleanExtra(ConfigManager.KEY_AGGRESSIVE_RECHECK, true)

        HookUtils.putGlobalString(context.contentResolver, ACTIVE_FLAG_KEY, "1")

        val rulesJson = intent.getStringExtra(ConfigManager.KEY_APP_RULES)
        if (!rulesJson.isNullOrEmpty()) {
            try {
                val obj = JSONObject(rulesJson)
                appRules.clear()
                for (key in obj.keys()) {
                    appRules[key] = obj.getString(key)
                }
                isConfigLoaded = true
                HookUtils.log("Config updated via broadcast: ${appRules.size} rules, default=$defaultImeId, enabled=$isGlobalEnabled")
            } catch (e: Exception) {
                HookUtils.logE("Failed to parse app rules JSON", e)
            }
        }

        val groupsJson = intent.getStringExtra(ConfigManager.KEY_GROUPS)
        if (!groupsJson.isNullOrEmpty()) {
            try {
                val arr = org.json.JSONArray(groupsJson)
                groupsMap.clear()
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val id = item.optString("id")
                    val ime = item.optString("targetImeId")
                    if (id.isNotEmpty()) {
                        groupsMap[id] = ime
                    }
                }
            } catch (e: Exception) {
                HookUtils.logE("Failed to parse groups JSON", e)
            }
        }

        currentForegroundPackage?.let { handleForegroundAppChanged(it, force = true) }
    }

    private fun handleForegroundAppChanged(packageName: String, force: Boolean = false) {
        if (!force && packageName == currentForegroundPackage) {
            return
        }
        currentForegroundPackage = packageName

        // Ensure configuration and broadcast receiver are alive
        if (!isConfigLoaded || !isReceiverRegistered) {
            ensureConfigAndReceiver()
        }

        if (!isGlobalEnabled) {
            return
        }

        val targetIme = getTargetImeForPackage(packageName)
        if (targetIme.isNullOrEmpty()) {
            return
        }

        switchExecutor.execute {
            applyImeSwitch(targetIme, packageName)
        }
    }

    private fun getTargetImeForPackage(pkg: String?): String? {
        if (pkg.isNullOrEmpty()) return null
        val mapped = appRules[pkg]
        if (!mapped.isNullOrEmpty()) {
            if (mapped == ConfigManager.RULE_KEEP_CURRENT) {
                return null // Explicitly configured to keep current IME
            }
            if (mapped.startsWith(ConfigManager.PREFIX_GROUP)) {
                val groupId = mapped.removePrefix(ConfigManager.PREFIX_GROUP)
                val targetIme = groupsMap[groupId]
                if (targetIme == ConfigManager.RULE_KEEP_CURRENT || targetIme.isNullOrEmpty()) {
                    return null // Group configured to keep current
                }
                return targetIme
            }
            return mapped
        }

        // Default behavior: IME apps default to keep current IME to prevent switching loops/glitches
        if (isImePackage(pkg)) {
            HookUtils.log("Package [$pkg] is an IME app, keeping current IME by default")
            return null
        }

        return defaultImeId.ifEmpty { null }
    }

    private fun isImePackage(pkg: String): Boolean {
        if (knownImePackages.contains(pkg)) return true
        val ctx = systemContext ?: HookUtils.getSystemContext() ?: return false
        return try {
            val intent = Intent("android.view.InputMethod").setPackage(pkg)
            val services = ctx.packageManager.queryIntentServices(intent, android.content.pm.PackageManager.MATCH_ALL)
            if (services.isNotEmpty()) {
                knownImePackages.add(pkg)
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    private fun applyImeSwitch(targetIme: String, triggerPkg: String) {
        val ctx = systemContext ?: HookUtils.getSystemContext()?.also { systemContext = it } ?: return
        val currentUserId = HookUtils.getCurrentUserId()

        val token = android.os.Binder.clearCallingIdentity()
        try {
            val currentIme = HookUtils.getSecureStringForUser(
                ctx.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                currentUserId
            )
            if (targetIme == currentIme) {
                lastTargetIme = targetIme
                return
            }

            HookUtils.log("Switching IME for [$triggerPkg]: current=$currentIme -> target=$targetIme")

            // 1. Write to Settings.Secure with system privileges
            HookUtils.putSecureStringForUser(
                ctx.contentResolver,
                Settings.Secure.DEFAULT_INPUT_METHOD,
                targetIme,
                currentUserId
            )
            lastTargetIme = targetIme

            // 2. Direct IMMS reflection call within system_server
            tryInvokeImmsSetInputMethod(targetIme, currentUserId)
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }

        // 3. Post-Switch Delayed Recheck asynchronously via switchExecutor
        if (aggressiveRecheck) {
            switchExecutor.schedule({
                verifyAndEnforceIme(targetIme, "DelayedVerification")
            }, 250, TimeUnit.MILLISECONDS)
        }
    }

    private fun tryInvokeImmsSetInputMethod(targetIme: String, userId: Int) {
        val imms = immsInstance ?: return
        val candidateMethodNames = listOf(
            "setInputMethodLocked",
            "setSelectedInputMethodAndSubtypeLocked",
            "updateInputMethodsFromSettingsLocked"
        )
        for (m in imms.javaClass.declaredMethods) {
            if (m.name in candidateMethodNames) {
                m.isAccessible = true
                try {
                    val paramTypes = m.parameterTypes
                    when (paramTypes.size) {
                        1 -> {
                            if (paramTypes[0] == Boolean::class.javaPrimitiveType) {
                                m.invoke(imms, true)
                                HookUtils.log("Invoked IMMS.${m.name}(true)")
                                return
                            }
                        }
                        2 -> {
                            if (paramTypes[0] == String::class.java && paramTypes[1] == Int::class.javaPrimitiveType) {
                                m.invoke(imms, targetIme, -1)
                                HookUtils.log("Invoked IMMS.${m.name}($targetIme, -1)")
                                return
                            }
                        }
                        3 -> {
                            if (paramTypes[0] == String::class.java &&
                                paramTypes[1] == Int::class.javaPrimitiveType &&
                                paramTypes[2] == Int::class.javaPrimitiveType) {
                                m.invoke(imms, targetIme, -1, userId)
                                HookUtils.log("Invoked IMMS.${m.name}($targetIme, -1, $userId)")
                                return
                            }
                        }
                    }
                } catch (t: Throwable) {
                    HookUtils.log("IMMS.${m.name} invoke attempt: ${t.message}")
                }
            }
        }
    }

    private fun verifyAndEnforceIme(expectedIme: String, source: String) {
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
                tryInvokeImmsSetInputMethod(expectedIme, currentUserId)
            }
        } catch (t: Throwable) {
            HookUtils.logE("Failed to enforce IME in recheck", t)
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }
}

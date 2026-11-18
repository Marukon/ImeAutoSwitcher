package io.github.marukon.imeauto.hook

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.marukon.imeauto.data.ConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object SystemServerHook {

    private const val ACTIVE_FLAG_KEY = "io_github_marukon_imeauto_active"
    private const val MODULE_PACKAGE = "io.github.marukon.imeauto"
    private const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"
    private const val EXTRA_USER_HANDLE = "android.intent.extra.user_handle"
    private const val PER_USER_RANGE = 100000

    private val TRANSIENT_SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.oplus.securitypermission",
        "com.android.intentresolver",
        "com.oplus.screenshot"
    )

    private class UserSessionState {
        @Volatile var dynamicDefaultIme: String = ""
        @Volatile var isCustomImeActive: Boolean = false
        @Volatile var lastProgrammaticTarget: String? = null
        @Volatile var userOverriddenInSession: Boolean = false
        @Volatile var activePackage: String? = null
        @Volatile var lastEvaluatedPackage: String? = null
        @Volatile var pendingVerificationTask: ScheduledFuture<*>? = null
    }

    private sealed interface RuleResolution {
        data class TargetCustom(val imeId: String) : RuleResolution
        object KeepCurrent : RuleResolution
        object Unmodified : RuleResolution
    }

    private val isInitStarted = AtomicBoolean(false)
    private val stateLock = Any()

    @Volatile
    private var module: XposedModule? = null

    @Volatile
    private var systemContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val switchExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor().apply {
            (this as? java.util.concurrent.ScheduledThreadPoolExecutor)?.removeOnCancelPolicy = true
        }

    @Volatile private var isForegroundHookInstalled = false
    @Volatile private var isImmsHookInstalled = false
    @Volatile private var isClipboardHookInstalled = false
    @Volatile private var isContextInitialized = false
    @Volatile private var isReceiverRegistered = false
    @Volatile private var isPackageReceiverRegistered = false
    @Volatile private var isSettingsObserverRegistered = false
    @Volatile private var isConfigLoaded = false
    @Volatile private var isActiveFlagWritten = false

    // Configuration cache
    @Volatile private var isGlobalEnabled = true
    @Volatile private var configuredDefaultIme = ""
    @Volatile private var aggressiveRecheck = false
    @Volatile private var appRules: Map<String, String> = emptyMap()
    @Volatile private var groupsMap: Map<String, String> = emptyMap()

    private val knownImePackages = ConcurrentHashMap.newKeySet<String>()
    private val nonImePackages = ConcurrentHashMap.newKeySet<String>()
    private val verifiedImeCache = ConcurrentHashMap.newKeySet<String>()

    private val userStates = ConcurrentHashMap<Int, UserSessionState>()

    @Volatile private var currentForegroundPackage: String? = null
    @Volatile private var currentUserId = 0

    private val lastFocusCheckTime = AtomicLong(0L)

    private fun stateFor(userId: Int): UserSessionState {
        return userStates.computeIfAbsent(userId) { UserSessionState() }
    }

    fun init(xposedModule: XposedModule, classLoader: ClassLoader) {
        if (!isInitStarted.compareAndSet(false, true)) return
        module = xposedModule

        if (!isContextInitialized) {
            HookUtils.getSystemContext()?.let { initSystemContext(it) }
        }

        if (!isForegroundHookInstalled) {
            hookActivityTaskManagerService(classLoader)
            hookTaskAndActivityRecord(classLoader)
        }

        if (!isImmsHookInstalled) {
            hookInputMethodManagerService(xposedModule, classLoader)
        }

        if (!isClipboardHookInstalled) {
            ClipboardSyncHook.installHooks(xposedModule, classLoader, systemContext)
            isClipboardHookInstalled = true
        }
    }

    private fun hookActivityTaskManagerService(classLoader: ClassLoader) {
        try {
            val atmsClass = try {
                Class.forName("com.android.server.wm.ActivityTaskManagerService", false, classLoader)
            } catch (e: ClassNotFoundException) {
                Class.forName("com.android.server.am.ActivityManagerService", false, classLoader)
            }

            HookUtils.findFirstMethodNamed(atmsClass, "onSystemReady")?.let { onSystemReady ->
                module?.hook(onSystemReady)
                    ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                    ?.intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val thisObj = chain.thisObject
                            val contextField = HookUtils.findFieldInHierarchy(thisObj.javaClass, "mContext")
                            (contextField?.get(thisObj) as? Context)?.let { initSystemContext(it) }
                            switchExecutor.execute { ensureConfigAndReceiver() }
                        } catch (t: Throwable) {
                            HookUtils.logE("Failed in onSystemReady intercept", t)
                        }
                        result
                    }
            }

            // Legacy Android 9/10 hook
            HookUtils.findFirstMethodNamed(atmsClass, "setResumedActivityUncheckLocked")?.let { method ->
                HookUtils.log("Found setResumedActivityUncheckLocked on ATMS, installing foreground hook")
                module?.hook(method)
                    ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                    ?.intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val activityRecord = chain.args.firstOrNull()
                            val pkg = HookUtils.getPackageNameFromActivityRecord(activityRecord)
                            val userId = HookUtils.extractUserId(activityRecord) ?: HookUtils.getCurrentUserId()
                            if (!pkg.isNullOrEmpty()) {
                                handleForegroundAppChanged(pkg, userId, isFromResumeHook = true)
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
        // Modern Android 13-17: TaskFragment.setResumedActivity(ActivityRecord, String)
        // Fallback Android 11-12: Task.setResumedActivity(ActivityRecord, String)
        val taskClassesToTry = listOf("com.android.server.wm.TaskFragment", "com.android.server.wm.Task")
        for (className in taskClassesToTry) {
            try {
                val cls = Class.forName(className, false, classLoader)
                val setResumedMethod = HookUtils.findFirstMethodNamed(cls, "setResumedActivity")
                if (setResumedMethod != null) {
                    HookUtils.log("Found $className.setResumedActivity, installing foreground hook")
                    module?.hook(setResumedMethod)
                        ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                        ?.intercept { chain ->
                            val result = chain.proceed()
                            try {
                                val activityRecord = chain.args.firstOrNull()
                                if (activityRecord != null) {
                                    val pkg = HookUtils.getPackageNameFromActivityRecord(activityRecord)
                                    val userId = HookUtils.extractUserId(activityRecord) ?: HookUtils.getCurrentUserId()
                                    if (!pkg.isNullOrEmpty()) {
                                        handleForegroundAppChanged(pkg, userId, isFromResumeHook = true)
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
                HookUtils.log("$className.setResumedActivity hook skip: ${t.message}")
            }
        }

        // Only install ActivityRecord fallback if no primary foreground hook was installed
        if (isForegroundHookInstalled) return

        try {
            val recordClass = Class.forName("com.android.server.wm.ActivityRecord", false, classLoader)
            val onTopMethod = HookUtils.findFirstMethodNamed(recordClass, "scheduleTopResumedActivityChanged")
                ?: HookUtils.findFirstMethodNamed(recordClass, "onTopResumedActivityChanged")
            if (onTopMethod != null) {
                HookUtils.log("Found fallback ActivityRecord.${onTopMethod.name}, installing foreground hook")
                module?.hook(onTopMethod)
                    ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                    ?.intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val isTop = (chain.args.firstOrNull() as? Boolean) ?: true
                            if (isTop) {
                                val thisObj = chain.thisObject
                                val pkg = HookUtils.getPackageNameFromActivityRecord(thisObj)
                                val userId = HookUtils.extractUserId(thisObj) ?: HookUtils.getCurrentUserId()
                                if (!pkg.isNullOrEmpty()) {
                                    handleForegroundAppChanged(pkg, userId, isFromResumeHook = true)
                                }
                            }
                        } catch (t: Throwable) {
                            HookUtils.logE("Error in ActivityRecord top resumed intercept", t)
                        }
                        result
                    }
                isForegroundHookInstalled = true
            }
        } catch (t: Throwable) {
            HookUtils.log("ActivityRecord top resumed hook error: ${t.message}")
        }
    }

    private fun hookInputMethodManagerService(xposedModule: XposedModule, classLoader: ClassLoader) {
        ImmsBridge.installHooks(
            module = xposedModule,
            classLoader = classLoader,
            onContextFound = { ctx ->
                if (!isContextInitialized) {
                    initSystemContext(ctx)
                }
            },
            onExternalSwitch = { imeId, userId ->
                handleExternalImeSwitch(imeId, userId)
            }
        )

        var hookedFocus = false
        try {
            val immsClass = Class.forName(
                "com.android.server.inputmethod.InputMethodManagerService",
                false,
                classLoader
            )
            hookedFocus = hookInputFocusMethods(immsClass, "startInputOrWindowGainedFocus") ||
                hookInputFocusMethods(immsClass, "startInputOrWindowGainedFocusAsync")
        } catch (t: Throwable) {
            HookUtils.logE("Error hooking IMMS startInput methods", t)
        }

        // Only fallback to IInputMethodManagerImpl if IMMS methods weren't found
        if (!hookedFocus) {
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
        isImmsHookInstalled = true
    }

    private fun hookInputFocusMethods(targetClass: Class<*>, methodName: String): Boolean {
        val methods = targetClass.declaredMethods.filter { it.name == methodName }
        if (methods.isEmpty()) return false

        var installedCount = 0
        for (m in methods) {
            try {
                val paramTypes = m.parameterTypes
                val rrIdx = paramTypes.indexOfFirst { it == ResultReceiver::class.java }
                val userIdArgIdx = if (rrIdx > 0 && paramTypes[rrIdx - 1] == Int::class.javaPrimitiveType) {
                    rrIdx - 1
                } else {
                    -1
                }

                module?.hook(m)
                    ?.setPriority(XposedInterface.PRIORITY_DEFAULT)
                    ?.intercept { chain ->
                        try {
                            if (isGlobalEnabled) {
                                val args = chain.args
                                val editorInfo = args.firstOrNull { it is EditorInfo } as? EditorInfo
                                val userId = if (userIdArgIdx >= 0) {
                                    (args.getOrNull(userIdArgIdx) as? Int) ?: currentUserId
                                } else {
                                    currentUserId
                                }
                                val editorPkg = editorInfo?.packageName
                                val targetPkg = if (!editorPkg.isNullOrEmpty()) editorPkg else currentForegroundPackage

                                if (!targetPkg.isNullOrEmpty()) {
                                    syncImeBeforeInputFocus(targetPkg, userId, hasEditor = editorInfo != null)
                                }
                            }
                        } catch (t: Throwable) {
                            HookUtils.logE("Error in ${targetClass.simpleName}.${m.name} pre-switch", t)
                        }
                        chain.proceed()
                    }
                installedCount++
                HookUtils.log("Hooked ${targetClass.name}.${m.name} (userIdIdx=$userIdArgIdx)")
            } catch (t: Throwable) {
                HookUtils.logE("Failed to hook ${targetClass.name}.${m.name}", t)
            }
        }
        return installedCount > 0
    }

    /**
     * Synchronously ensures IMMS & Settings.Secure reflect the expected IME before
     * IMMS acquires ImfLock inside startInputOrWindowGainedFocus.
     */
    private fun syncImeBeforeInputFocus(packageName: String, userId: Int, hasEditor: Boolean) {
        if (!hasEditor && TRANSIENT_SYSTEM_PACKAGES.contains(packageName)) {
            return
        }

        val state = stateFor(userId)
        val isPackageChanged = synchronized(stateLock) {
            if (packageName != currentForegroundPackage || userId != currentUserId) {
                if (TRANSIENT_SYSTEM_PACKAGES.contains(packageName)) {
                    return
                }
                currentForegroundPackage = packageName
                currentUserId = userId
                if (state.activePackage != packageName) {
                    state.userOverriddenInSession = false
                    state.activePackage = packageName
                }
                true
            } else {
                false
            }
        }

        if (state.userOverriddenInSession) {
            return
        }

        val now = android.os.SystemClock.uptimeMillis()
        val last = lastFocusCheckTime.get()
        val needsEvaluation = isPackageChanged || state.lastEvaluatedPackage != packageName
        val shouldCheck = needsEvaluation ||
            (aggressiveRecheck && state.isCustomImeActive && now - last > 150 && lastFocusCheckTime.compareAndSet(last, now))

        if (!shouldCheck) return

        applyRuleForPackage(packageName, userId, triggerSource = "StartInput")
    }

    private fun handleExternalImeSwitch(imeId: String, userId: Int) {
        if (!isValidImeId(imeId)) return
        val state = stateFor(userId)
        val normalizedNew = HookUtils.normalizeImeId(imeId) ?: imeId
        val normalizedProg = HookUtils.normalizeImeId(state.lastProgrammaticTarget)

        if (state.isCustomImeActive) {
            if (normalizedNew != normalizedProg) {
                state.userOverriddenInSession = true
                state.pendingVerificationTask?.cancel(false)
                HookUtils.log("Manual IME override detected in custom session (user=$userId): $imeId; pausing enforcement")
            }
        } else if (configuredDefaultIme.isEmpty()) {
            if (state.dynamicDefaultIme != normalizedNew) {
                HookUtils.log("External default IME updated (user=$userId): ${state.dynamicDefaultIme} -> $normalizedNew")
                state.dynamicDefaultIme = normalizedNew
            }
        }

        switchExecutor.execute {
            ClipboardSyncHook.notifyMissedClipForIme(normalizedNew, userId)
        }
    }

    private fun initSystemContext(context: Context) {
        if (isContextInitialized) return
        systemContext = context
        ClipboardSyncHook.updateContext(context)
        isContextInitialized = true
        HookUtils.log("System context registered, writing boot-scoped active handshake flag")

        ensureActiveFlagWritten(context)

        switchExecutor.schedule({ ensureConfigAndReceiver() }, 1000, TimeUnit.MILLISECONDS)
        switchExecutor.schedule({ ensureConfigAndReceiver() }, 3000, TimeUnit.MILLISECONDS)
        switchExecutor.schedule({ ensureConfigAndReceiver() }, 8000, TimeUnit.MILLISECONDS)
    }

    private fun ensureActiveFlagWritten(context: Context) {
        if (isActiveFlagWritten) return
        try {
            val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            if (bootCount >= 0) {
                val ok = HookUtils.putGlobalString(context.contentResolver, ACTIVE_FLAG_KEY, "boot:$bootCount")
                if (ok) {
                    isActiveFlagWritten = true
                    HookUtils.log("Marked $ACTIVE_FLAG_KEY=boot:$bootCount in Settings.Global")
                }
            }
        } catch (t: Throwable) {
            HookUtils.log("Active flag write deferred until SettingsProvider ready: ${t.message}")
        }
    }

    private fun ensureConfigAndReceiver() {
        val ctx = systemContext ?: HookUtils.getSystemContext()?.also { systemContext = it } ?: return

        ensureActiveFlagWritten(ctx)
        if (!isConfigLoaded) {
            loadConfigFromAllSources(ctx)
        }
        if (!isSettingsObserverRegistered) {
            registerSettingsObserver(ctx)
        }
        if (!isReceiverRegistered) {
            registerConfigReceiver(ctx)
        }
        if (!isPackageReceiverRegistered) {
            registerPackageReceiver(ctx)
        }
    }

    private fun registerSettingsObserver(context: Context) {
        if (isSettingsObserverRegistered) return
        try {
            val configUri = Settings.Global.getUriFor(ConfigManager.SETTINGS_CONFIG_KEY)
            context.contentResolver.registerContentObserver(
                configUri,
                false,
                object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        switchExecutor.execute {
                            HookUtils.log("Settings.Global config changed, reloading...")
                            loadConfigFromAllSources(context)
                        }
                    }
                }
            )

            // Invalidate IME enabled cache when user enables/disables IMEs in system settings
            val enabledImeUri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS)
            context.contentResolver.registerContentObserver(
                enabledImeUri,
                false,
                object : ContentObserver(mainHandler) {
                    override fun onChange(selfChange: Boolean) {
                        verifiedImeCache.clear()
                    }
                }
            )

            isSettingsObserverRegistered = true
            HookUtils.log("Registered SettingsObservers for config and enabled IMEs")
        } catch (t: Throwable) {
            HookUtils.log("registerSettingsObserver deferred: ${t.message}")
        }
    }

    private fun isAuthorizedSyncBroadcast(context: Context, receiver: BroadcastReceiver, intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val sentPkg = runCatching { receiver.sentFromPackage }.getOrNull()
            if (!sentPkg.isNullOrEmpty() && sentPkg != MODULE_PACKAGE && sentPkg != "android") {
                HookUtils.log("Rejected ACTION_SYNC_CONFIG from unexpected package: $sentPkg")
                return false
            }
        }

        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(ConfigManager.EXTRA_SENDER_TOKEN, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(ConfigManager.EXTRA_SENDER_TOKEN)
        }
        if (token == null || token.creatorPackage != MODULE_PACKAGE) {
            HookUtils.log("Rejected ACTION_SYNC_CONFIG missing valid module sender token (creator=${token?.creatorPackage})")
            return false
        }

        return try {
            val expectedAppId = context.packageManager.getApplicationInfo(MODULE_PACKAGE, 0).uid % PER_USER_RANGE
            val tokenAppId = token.creatorUid % PER_USER_RANGE
            val matches = tokenAppId == expectedAppId
            if (!matches) {
                HookUtils.log("Rejected ACTION_SYNC_CONFIG due to UID mismatch: $tokenAppId != $expectedAppId")
            }
            matches
        } catch (t: Throwable) {
            HookUtils.log("Rejected ACTION_SYNC_CONFIG while verifying module UID: ${t.message}")
            false
        }
    }

    private fun registerConfigReceiver(context: Context) {
        try {
            // 1. Internal system_server user switch broadcast (not exported to third-party apps)
            val userSwitchFilter = IntentFilter(ACTION_USER_SWITCHED)
            val userSwitchReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action != ACTION_USER_SWITCHED) return
                    try {
                        val newUserId = intent.getIntExtra(EXTRA_USER_HANDLE, 0)
                        HookUtils.log("User switched to $newUserId")
                        synchronized(stateLock) {
                            currentUserId = newUserId
                            currentForegroundPackage = null
                        }
                        switchExecutor.execute { ensureConfigAndReceiver() }
                    } catch (t: Throwable) {
                        HookUtils.logE("Error processing USER_SWITCHED broadcast", t)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(userSwitchReceiver, userSwitchFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(userSwitchReceiver, userSwitchFilter)
            }

            // 2. Module config sync broadcast gated by WRITE_SECURE_SETTINGS + PendingIntent sender token
            val syncFilter = IntentFilter(ConfigManager.ACTION_SYNC_CONFIG)
            val syncReceiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    if (intent?.action != ConfigManager.ACTION_SYNC_CONFIG) return
                    try {
                        if (!isAuthorizedSyncBroadcast(context, this, intent)) {
                            return
                        }
                        if (intent.hasExtra(ConfigManager.SETTINGS_CONFIG_KEY) || intent.hasExtra(ConfigManager.KEY_APP_RULES)) {
                            applyConfigFromIntent(intent, context)
                        }
                    } catch (t: Throwable) {
                        HookUtils.logE("Error processing ACTION_SYNC_CONFIG broadcast", t)
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    syncReceiver,
                    syncFilter,
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    null,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                context.registerReceiver(
                    syncReceiver,
                    syncFilter,
                    android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    null
                )
            }
            isReceiverRegistered = true
            HookUtils.log("Registered authenticated ACTION_SYNC_CONFIG and internal ACTION_USER_SWITCHED receivers")
        } catch (t: Throwable) {
            HookUtils.log("registerConfigReceiver deferred: ${t.message}")
        }
    }

    private fun registerPackageReceiver(context: Context) {
        if (isPackageReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val pkg = intent?.data?.schemeSpecificPart ?: return
                    verifiedImeCache.removeIf { it.contains("|$pkg/") }
                    knownImePackages.remove(pkg)
                    nonImePackages.remove(pkg)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            isPackageReceiverRegistered = true
        } catch (t: Throwable) {
            HookUtils.log("registerPackageReceiver deferred: ${t.message}")
        }
    }

    fun isValidImeId(id: String?): Boolean {
        if (id.isNullOrEmpty()) return false
        if (id == ConfigManager.RULE_KEEP_CURRENT) return false
        if (id.startsWith(ConfigManager.PREFIX_GROUP)) return false
        val slashIndex = id.indexOf('/')
        return slashIndex > 0 && slashIndex < id.length - 1
    }

    fun isImeInstalledAndEnabled(imeId: String, userId: Int): Boolean {
        if (!isValidImeId(imeId)) return false
        val normalized = HookUtils.normalizeImeId(imeId) ?: imeId
        val cacheKey = "$userId|$normalized"
        if (verifiedImeCache.contains(cacheKey)) return true

        val comp = ComponentName.unflattenFromString(normalized) ?: return false
        val ctx = systemContext ?: HookUtils.getSystemContext() ?: return true
        return try {
            val intent = Intent("android.view.InputMethod").setComponent(comp)
            val services = ctx.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
            if (services.isEmpty()) {
                return false
            }
            // Check ENABLED_INPUT_METHODS for this user (or primary user 0 for MultiApp profiles)
            val enabled = HookUtils.isImeEnabledInSecureSettings(ctx.contentResolver, normalized, userId) ||
                (userId != 0 && HookUtils.isImeEnabledInSecureSettings(ctx.contentResolver, normalized, 0))
            if (enabled) {
                verifiedImeCache.add(cacheKey)
            }
            enabled
        } catch (t: Throwable) {
            true
        }
    }

    private fun parseGroupsJson(groupsRaw: Any) {
        try {
            val groupsArr = when (groupsRaw) {
                is JSONArray -> groupsRaw
                is String -> JSONArray(groupsRaw)
                else -> null
            } ?: return

            val newGroups = HashMap<String, String>()
            for (i in 0 until groupsArr.length()) {
                val item = groupsArr.getJSONObject(i)
                val id = item.optString("id")
                val ime = item.optString("targetImeId")
                if (id.isNotEmpty() && (ime == ConfigManager.RULE_KEEP_CURRENT || isValidImeId(ime))) {
                    newGroups[id] = ime
                }
            }
            groupsMap = newGroups
        } catch (e: Throwable) {
            HookUtils.logE("Failed to parse groups JSON", e)
        }
    }

    private fun parseAppRulesObject(rulesObj: JSONObject): Map<String, String> {
        val newRules = HashMap<String, String>()
        for (key in rulesObj.keys()) {
            if (key.isNullOrEmpty()) continue
            val value = rulesObj.optString(key, "")
            if (value == ConfigManager.RULE_KEEP_CURRENT ||
                value.startsWith(ConfigManager.PREFIX_GROUP) ||
                isValidImeId(value)
            ) {
                newRules[key] = value
            }
        }
        return newRules
    }

    private fun loadConfigFromAllSources(context: Context) {
        var loaded = false
        try {
            val globalConfig = Settings.Global.getString(context.contentResolver, ConfigManager.SETTINGS_CONFIG_KEY)
            if (!globalConfig.isNullOrEmpty()) {
                loaded = applyConfigFromJsonString(globalConfig, "Settings.Global")
            }
        } catch (t: Throwable) {
            HookUtils.log("Settings.Global read error: ${t.message}")
        }

        if (!loaded) {
            try {
                val file = File(ConfigManager.SYSTEM_CONFIG_PATH)
                if (file.exists() && file.canRead()) {
                    val content = file.readText()
                    if (content.isNotEmpty()) {
                        loaded = applyConfigFromJsonString(content, "File(/data/system)")
                    }
                }
            } catch (t: Throwable) {
                HookUtils.log("File read error: ${t.message}")
            }
        }

        if (!loaded) {
            loadConfigFromProvider(context)
        }
    }

    private fun applyConfigFromJsonString(jsonStr: String, source: String): Boolean {
        return try {
            val root = JSONObject(jsonStr)
            if (root.has(ConfigManager.KEY_GLOBAL_ENABLED)) {
                isGlobalEnabled = root.optBoolean(ConfigManager.KEY_GLOBAL_ENABLED, true)
            }
            if (root.has(ConfigManager.KEY_DEFAULT_IME)) {
                val ime = root.optString(ConfigManager.KEY_DEFAULT_IME, "")
                configuredDefaultIme = if (isValidImeId(ime)) ime else ""
            }
            if (root.has(ConfigManager.KEY_AGGRESSIVE_RECHECK)) {
                aggressiveRecheck = root.optBoolean(ConfigManager.KEY_AGGRESSIVE_RECHECK, false)
            }
            if (root.has(ConfigManager.KEY_APP_RULES)) {
                val rulesRaw = root.get(ConfigManager.KEY_APP_RULES)
                val rulesObj = when (rulesRaw) {
                    is JSONObject -> rulesRaw
                    is String -> JSONObject(rulesRaw)
                    else -> null
                }
                if (rulesObj != null) {
                    appRules = parseAppRulesObject(rulesObj)
                }
            }
            if (root.has(ConfigManager.KEY_GROUPS)) {
                parseGroupsJson(root.get(ConfigManager.KEY_GROUPS))
            }
            if (root.has(ConfigManager.KEY_KNOWN_IME_PACKAGES)) {
                val imeRaw = root.get(ConfigManager.KEY_KNOWN_IME_PACKAGES)
                val imeArr = when (imeRaw) {
                    is JSONArray -> imeRaw
                    is String -> JSONArray(imeRaw)
                    else -> null
                }
                if (imeArr != null) {
                    knownImePackages.clear()
                    for (i in 0 until imeArr.length()) {
                        knownImePackages.add(imeArr.getString(i))
                    }
                }
            }
            isConfigLoaded = true
            HookUtils.log("Config applied from [$source]: ${appRules.size} rules, ${groupsMap.size} groups, configuredDefault='$configuredDefaultIme', aggressive=$aggressiveRecheck, enabled=$isGlobalEnabled")
            currentForegroundPackage?.let { handleForegroundAppChanged(it, userId = currentUserId, force = true) }
            true
        } catch (e: Throwable) {
            HookUtils.logE("Failed to parse config from [$source]", e)
            false
        }
    }

    private fun loadConfigFromProvider(context: Context) {
        try {
            val uri = Uri.parse("content://io.github.marukon.imeauto.provider")
            val bundle = context.contentResolver.call(uri, "getConfig", null, null) ?: return
            val fullJson = bundle.getString(ConfigManager.SETTINGS_CONFIG_KEY)
            if (!fullJson.isNullOrEmpty() && applyConfigFromJsonString(fullJson, "Provider.fullJson")) {
                return
            }

            isGlobalEnabled = bundle.getBoolean(ConfigManager.KEY_GLOBAL_ENABLED, true)
            val ime = bundle.getString(ConfigManager.KEY_DEFAULT_IME, "") ?: ""
            configuredDefaultIme = if (isValidImeId(ime)) ime else ""
            aggressiveRecheck = bundle.getBoolean(ConfigManager.KEY_AGGRESSIVE_RECHECK, false)
            bundle.getString(ConfigManager.KEY_APP_RULES)?.takeIf { it.isNotEmpty() }?.let { raw ->
                appRules = parseAppRulesObject(JSONObject(raw))
            }
            bundle.getString(ConfigManager.KEY_GROUPS)?.takeIf { it.isNotEmpty() }?.let {
                parseGroupsJson(it)
            }
            isConfigLoaded = true
        } catch (t: Throwable) {
            HookUtils.log("loadConfigFromProvider deferred: ${t.message}")
        }
    }

    private fun applyConfigFromIntent(intent: Intent, context: Context) {
        try {
            val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
            HookUtils.putGlobalString(context.contentResolver, ACTIVE_FLAG_KEY, "boot:$bootCount")
        } catch (ignored: Throwable) {}

        val fullJson = intent.getStringExtra(ConfigManager.SETTINGS_CONFIG_KEY)
        if (!fullJson.isNullOrEmpty() && applyConfigFromJsonString(fullJson, "Broadcast.fullJson")) {
            return
        }

        isGlobalEnabled = intent.getBooleanExtra(ConfigManager.KEY_GLOBAL_ENABLED, true)
        val imeFromIntent = intent.getStringExtra(ConfigManager.KEY_DEFAULT_IME)
        configuredDefaultIme = if (isValidImeId(imeFromIntent)) imeFromIntent!! else ""
        aggressiveRecheck = intent.getBooleanExtra(ConfigManager.KEY_AGGRESSIVE_RECHECK, false)

        intent.getStringExtra(ConfigManager.KEY_APP_RULES)?.takeIf { it.isNotEmpty() }?.let { rulesJson ->
            runCatching {
                appRules = parseAppRulesObject(JSONObject(rulesJson))
                isConfigLoaded = true
            }.onFailure { HookUtils.logE("Failed to parse app rules JSON", it) }
        }

        intent.getStringExtra(ConfigManager.KEY_GROUPS)?.takeIf { it.isNotEmpty() }?.let {
            parseGroupsJson(it)
        }

        currentForegroundPackage?.let { handleForegroundAppChanged(it, userId = currentUserId, force = true) }
    }

    private fun handleForegroundAppChanged(
        packageName: String,
        userId: Int = HookUtils.getCurrentUserId(),
        force: Boolean = false,
        isFromResumeHook: Boolean = false
    ) {
        if (isFromResumeHook && TRANSIENT_SYSTEM_PACKAGES.contains(packageName)) {
            return
        }

        synchronized(stateLock) {
            if (!force && packageName == currentForegroundPackage && userId == currentUserId) {
                return
            }
            currentForegroundPackage = packageName
            currentUserId = userId

            val state = stateFor(userId)
            if (state.activePackage != packageName) {
                state.activePackage = packageName
                state.userOverriddenInSession = false
            }
        }

        switchExecutor.execute {
            try {
                val stale = synchronized(stateLock) {
                    !force && (packageName != currentForegroundPackage || userId != currentUserId)
                }
                if (stale) return@execute

                if (!isConfigLoaded || !isReceiverRegistered) {
                    ensureConfigAndReceiver()
                }
                if (!isGlobalEnabled) return@execute

                applyRuleForPackage(packageName, userId, triggerSource = "Foreground")
            } catch (t: Throwable) {
                HookUtils.logE("Error handling foreground app change for $packageName", t)
            }
        }
    }

    private fun applyRuleForPackage(packageName: String, userId: Int, triggerSource: String) {
        val state = stateFor(userId)
        synchronized(state) {
            state.lastEvaluatedPackage = packageName
            val resolution = resolveAppRule(packageName)

            when (resolution) {
                is RuleResolution.TargetCustom -> {
                    if (state.userOverriddenInSession) {
                        return
                    }
                    ensureDynamicDefaultCached(userId, state)

                    val customIme = resolution.imeId
                    val fallbackDefault = resolveFallbackDefaultIme(userId, state)
                    val isDifferentFromDefault = customIme != fallbackDefault
                    val switched = applyImeSwitch(customIme, "$triggerSource:$packageName", userId)
                    state.isCustomImeActive = switched && isDifferentFromDefault
                }
                is RuleResolution.KeepCurrent -> {
                    // Preserve current IME and session state untouched
                }
                is RuleResolution.Unmodified -> {
                    if (state.isCustomImeActive) {
                        val restoreTarget = resolveFallbackDefaultIme(userId, state)
                        if (isValidImeId(restoreTarget)) {
                            HookUtils.log("Exited custom app -> restoring default IME [$restoreTarget] (user=$userId, next=$packageName)")
                            applyImeSwitch(restoreTarget, "RestoreAfter:$packageName", userId)
                        }
                        state.isCustomImeActive = false
                        state.userOverriddenInSession = false
                    }
                }
            }
        }
    }

    private fun ensureDynamicDefaultCached(userId: Int, state: UserSessionState) {
        if (configuredDefaultIme.isNotEmpty() || state.dynamicDefaultIme.isNotEmpty()) return
        val ctx = systemContext ?: HookUtils.getSystemContext() ?: return
        val currentIme = ImmsBridge.getSelectedImeInImms(userId)
            ?: HookUtils.getSecureStringForUser(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, userId)
        if (!currentIme.isNullOrEmpty() && isValidImeId(currentIme)) {
            state.dynamicDefaultIme = HookUtils.normalizeImeId(currentIme) ?: currentIme
        }
    }

    private fun resolveFallbackDefaultIme(userId: Int, state: UserSessionState): String {
        if (configuredDefaultIme.isNotEmpty() && isValidImeId(configuredDefaultIme)) {
            return configuredDefaultIme
        }
        ensureDynamicDefaultCached(userId, state)
        return state.dynamicDefaultIme
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
            val services = ctx.packageManager.queryIntentServices(intent, PackageManager.MATCH_ALL)
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

    private fun applyImeSwitch(targetIme: String, triggerPkg: String, targetUser: Int): Boolean {
        val normalizedTarget = HookUtils.normalizeImeId(targetIme) ?: targetIme
        if (!isValidImeId(normalizedTarget)) {
            HookUtils.log("Refusing invalid IME ID: '$targetIme' for [$triggerPkg]")
            return false
        }
        if (!isImeInstalledAndEnabled(normalizedTarget, targetUser)) {
            HookUtils.log("Target IME [$normalizedTarget] is not installed/enabled for user=$targetUser, skipping [$triggerPkg]")
            return false
        }

        val ctx = systemContext ?: HookUtils.getSystemContext()?.also { systemContext = it } ?: return false
        val state = stateFor(targetUser)
        state.lastProgrammaticTarget = normalizedTarget

        val switchResult = ImmsBridge.switchSynchronously(ctx, normalizedTarget, targetUser)
        when (switchResult) {
            ImmsBridge.SwitchResult.NOT_ENABLED -> return false
            ImmsBridge.SwitchResult.ALREADY_ACTIVE -> {
                switchExecutor.execute {
                    ClipboardSyncHook.notifyMissedClipForIme(normalizedTarget, targetUser)
                }
                return true
            }
            ImmsBridge.SwitchResult.SWITCHED -> {
                HookUtils.log("Synchronously switched IME via IMMS for [$triggerPkg] (user=$targetUser) -> $normalizedTarget")
                switchExecutor.execute {
                    ClipboardSyncHook.notifyMissedClipForIme(normalizedTarget, targetUser)
                }
            }
            ImmsBridge.SwitchResult.UNAVAILABLE -> {
                val currentIme = HookUtils.getSecureStringForUser(
                    ctx.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    targetUser
                )
                if (HookUtils.normalizeImeId(currentIme) == normalizedTarget) {
                    switchExecutor.execute {
                        ClipboardSyncHook.notifyMissedClipForIme(normalizedTarget, targetUser)
                    }
                    return true
                }
                HookUtils.log("Switching IME via Settings.Secure fallback for [$triggerPkg] (user=$targetUser): $currentIme -> $normalizedTarget")
                HookUtils.putSecureStringForUser(
                    ctx.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    normalizedTarget,
                    targetUser
                )
                switchExecutor.execute {
                    ClipboardSyncHook.notifyMissedClipForIme(normalizedTarget, targetUser)
                }
            }
        }

        if (aggressiveRecheck) {
            state.pendingVerificationTask?.cancel(false)
            state.pendingVerificationTask = switchExecutor.schedule({
                verifyAndEnforceIme(normalizedTarget, "DelayedVerification", targetUser)
            }, 250, TimeUnit.MILLISECONDS)
        }
        return true
    }

    private fun verifyAndEnforceIme(expectedIme: String, source: String, targetUser: Int) {
        val state = stateFor(targetUser)
        if (state.userOverriddenInSession || !isGlobalEnabled) return
        if (!isValidImeId(expectedIme) || !isImeInstalledAndEnabled(expectedIme, targetUser)) return
        if (HookUtils.normalizeImeId(state.lastProgrammaticTarget) != HookUtils.normalizeImeId(expectedIme)) {
            return
        }

        val ctx = systemContext ?: HookUtils.getSystemContext()?.also { systemContext = it } ?: return
        val current = ImmsBridge.getSelectedImeInImms(targetUser)
            ?: HookUtils.getSecureStringForUser(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, targetUser)
        if (HookUtils.normalizeImeId(current) != HookUtils.normalizeImeId(expectedIme)) {
            HookUtils.log("Recheck [$source] (user=$targetUser): mismatch ($current != $expectedIme), enforcing...")
            if (ImmsBridge.switchSynchronously(ctx, expectedIme, targetUser) == ImmsBridge.SwitchResult.UNAVAILABLE) {
                HookUtils.putSecureStringForUser(
                    ctx.contentResolver,
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    expectedIme,
                    targetUser
                )
            }
            ClipboardSyncHook.notifyMissedClipForIme(expectedIme, targetUser)
        }
    }
}

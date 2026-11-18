package io.github.marukon.imeauto.hook

import android.content.ClipData
import android.content.Context
import android.os.Binder
import android.os.RemoteCallbackList
import android.provider.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tracks primary clipboard updates in system_server's ClipboardService and replays
 * missed OnPrimaryClipChanged callbacks when ImeAutoSwitcher switches to an IME
 * that was not the active default_input_method at the time of copy.
 *
 * Locking rule: Never acquire ClipboardService.mLock while holding ImfLock.
 */
object ClipboardSyncHook {

    private const val PER_USER_RANGE = 100000

    private class ClipState(
        val generation: Long,
        val hasClip: Boolean,
        val notifiedPackages: MutableSet<String> = ConcurrentHashMap.newKeySet()
    )

    @Volatile
    private var clipboardServiceInstance: Any? = null

    @Volatile
    private var clipboardLock: Any? = null

    @Volatile
    private var systemContext: Context? = null

    private var getClipboardLockedMethod: Method? = null
    private var dispatchPrimaryClipChangedMethod: Method? = null

    private var clipboardUserIdField: Field? = null
    private var clipboardDeviceIdField: Field? = null
    private var clipboardPrimaryClipField: Field? = null
    private var clipboardListenersField: Field? = null

    private var listenerPkgField: Field? = null
    private var listenerUidField: Field? = null

    private val clipGenCounter = AtomicLong(0L)
    private val clipStatesByUser = ConcurrentHashMap<Int, ClipState>()

    @Volatile
    private var isHooked = false

    fun installHooks(module: XposedModule, classLoader: ClassLoader, context: Context?) {
        if (context != null && systemContext == null) {
            systemContext = context
        }
        if (isHooked) return

        try {
            val csClass = Class.forName(
                "com.android.server.clipboard.ClipboardService",
                false,
                classLoader
            )

            cacheFieldsAndMethods(csClass, classLoader)

            for (ctor in csClass.declaredConstructors) {
                module.hook(ctor)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val inst = chain.thisObject
                        clipboardServiceInstance = inst
                        resolveLock(inst)
                        if (systemContext == null) {
                            chain.args.firstOrNull { it is Context }?.let {
                                systemContext = it as Context
                            }
                        }
                        HookUtils.log("Captured ClipboardService instance")
                        result
                    }
            }

            val setClipMethods = csClass.declaredMethods.filter {
                it.name == "setPrimaryClipInternalNoClassifyLocked"
            }
            for (method in setClipMethods) {
                module.hook(method)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            onPrimaryClipUpdated(chain.thisObject, chain.args)
                        } catch (t: Throwable) {
                            HookUtils.logE("Error tracking clipboard update", t)
                        }
                        result
                    }
            }

            isHooked = true
            HookUtils.log("Installed ClipboardSyncHook on ClipboardService (${setClipMethods.size} clip hooks)")
        } catch (t: Throwable) {
            HookUtils.logE("Failed to install ClipboardSyncHook", t)
        }
    }

    fun updateContext(context: Context) {
        if (systemContext == null) {
            systemContext = context
        }
    }

    private fun cacheFieldsAndMethods(csClass: Class<*>, classLoader: ClassLoader) {
        getClipboardLockedMethod = runCatching {
            csClass.getDeclaredMethod(
                "getClipboardLocked",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrNull()

        runCatching {
            val clipboardClass = Class.forName(
                "com.android.server.clipboard.ClipboardService\$Clipboard",
                false,
                classLoader
            )
            clipboardUserIdField = HookUtils.findFieldInHierarchy(clipboardClass, "userId")
            clipboardDeviceIdField = HookUtils.findFieldInHierarchy(clipboardClass, "deviceId")
            clipboardPrimaryClipField = HookUtils.findFieldInHierarchy(clipboardClass, "primaryClip")
            clipboardListenersField = HookUtils.findFieldInHierarchy(clipboardClass, "primaryClipListeners")
        }

        runCatching {
            val listenerInfoClass = Class.forName(
                "com.android.server.clipboard.ClipboardService\$ListenerInfo",
                false,
                classLoader
            )
            listenerPkgField = HookUtils.findFieldInHierarchy(listenerInfoClass, "mPackageName")
            listenerUidField = HookUtils.findFieldInHierarchy(listenerInfoClass, "mUid")
        }
    }

    private fun resolveLock(instance: Any?) {
        if (clipboardLock != null || instance == null) return
        val lockField = HookUtils.findFieldInHierarchy(instance.javaClass, "mLock")
        clipboardLock = runCatching { lockField?.get(instance) }.getOrNull()
    }

    private fun onPrimaryClipUpdated(serviceObj: Any?, args: List<Any?>) {
        if (clipboardServiceInstance == null && serviceObj != null) {
            clipboardServiceInstance = serviceObj
            resolveLock(serviceObj)
        }
        val clipboardObj = args.getOrNull(0) ?: return
        val clip = args.getOrNull(1) as? ClipData

        val userId = runCatching { clipboardUserIdField?.getInt(clipboardObj) }.getOrNull() ?: return
        val deviceId = runCatching { clipboardDeviceIdField?.getInt(clipboardObj) }.getOrNull() ?: 0
        if (deviceId != 0) return

        if (clip == null) {
            clipStatesByUser.remove(userId)
            return
        }

        val state = ClipState(
            generation = clipGenCounter.incrementAndGet(),
            hasClip = true
        )

        // Record the IME package that was active at copy time (it already received the normal broadcast)
        val ctx = systemContext ?: HookUtils.getSystemContext()?.also { systemContext = it }
        val activeDefaultIme = ImmsBridge.getSelectedImeInImms(userId) ?: ctx?.contentResolver?.let {
            HookUtils.getSecureStringForUser(it, Settings.Secure.DEFAULT_INPUT_METHOD, userId)
        }
        val activeImePkg = activeDefaultIme?.substringBefore('/')
        if (!activeImePkg.isNullOrEmpty()) {
            state.notifiedPackages.add(activeImePkg)
        }

        // Also record source package if present (args[3] in 4-arg overload)
        val sourcePkg = args.getOrNull(3) as? String
        if (!sourcePkg.isNullOrEmpty()) {
            state.notifiedPackages.add(sourcePkg)
        }

        clipStatesByUser[userId] = state
    }

    /**
     * Dispatches a missed primaryClipChanged notification to registered listeners of [targetImeId]
     * if a clipboard item was copied while another IME was active.
     * Must NOT be called while holding ImfLock.
     */
    fun notifyMissedClipForIme(targetImeId: String, userId: Int) {
        val slash = targetImeId.indexOf('/')
        if (slash <= 0) return
        val targetPkg = targetImeId.substring(0, slash)

        val state = clipStatesByUser[userId] ?: return
        if (!state.hasClip) return
        if (state.notifiedPackages.contains(targetPkg)) return

        val cs = clipboardServiceInstance ?: return
        val lock = clipboardLock ?: return
        val getClipboard = getClipboardLockedMethod ?: return
        val listenersField = clipboardListenersField ?: return
        val primaryClipField = clipboardPrimaryClipField ?: return

        synchronized(lock) {
            // Re-check latest state inside lock
            val latestState = clipStatesByUser[userId] ?: return
            if (!latestState.hasClip || latestState.notifiedPackages.contains(targetPkg)) {
                return
            }

            val clipboardObj = runCatching { getClipboard.invoke(cs, userId, 0) }.getOrNull() ?: return
            @Suppress("UNUSED_VARIABLE")
            val primaryClip = runCatching { primaryClipField.get(clipboardObj) }.getOrNull() ?: return
            @Suppress("UNCHECKED_CAST")
            val listeners = runCatching {
                listenersField.get(clipboardObj) as? RemoteCallbackList<android.os.IInterface>
            }.getOrNull() ?: return

            val token = Binder.clearCallingIdentity()
            var broadcastStarted = false
            var replayedCount = 0
            try {
                val count = listeners.beginBroadcast()
                broadcastStarted = true
                for (i in 0 until count) {
                    try {
                        val cookie = listeners.getBroadcastCookie(i) ?: continue
                        val pkg = listenerPkgField?.get(cookie) as? String ?: continue
                        val uid = listenerUidField?.getInt(cookie) ?: -1
                        if (pkg == targetPkg && (uid / PER_USER_RANGE) == userId) {
                            val item = listeners.getBroadcastItem(i)
                            val dispatchMethod = dispatchPrimaryClipChangedMethod
                                ?: item.javaClass.getMethod("dispatchPrimaryClipChanged").also {
                                    it.isAccessible = true
                                    dispatchPrimaryClipChangedMethod = it
                                }
                            dispatchMethod.invoke(item)
                            replayedCount++
                            HookUtils.log("Replayed missed primaryClipChanged to [$targetPkg] for user=$userId (clipGen=${latestState.generation})")
                        }
                    } catch (t: Throwable) {
                        HookUtils.log("Skipped clipboard listener replay for [$targetPkg]: ${t.message}")
                    }
                }
                if (replayedCount > 0) {
                    latestState.notifiedPackages.add(targetPkg)
                }
            } catch (t: Throwable) {
                HookUtils.logE("Error broadcasting missed clipboard event to [$targetPkg]", t)
            } finally {
                if (broadcastStarted) {
                    runCatching { listeners.finishBroadcast() }
                }
                Binder.restoreCallingIdentity(token)
            }
        }
    }
}

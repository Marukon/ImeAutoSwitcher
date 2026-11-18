package io.github.marukon.imeauto.hook

import android.content.Context
import android.os.Binder
import android.provider.Settings
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * Synchronized bridge into Android's InputMethodManagerService (IMMS).
 * Acquires IMMS's internal lock (ImfLock.class on API 34+ or mMethodMap on legacy APIs)
 * before invoking switchToInputMethodLocked / setInputMethodLocked, eliminating async
 * state tearing and double-unbind races.
 */
object ImmsBridge {

    enum class SwitchResult {
        SWITCHED,
        ALREADY_ACTIVE,
        NOT_ENABLED,
        UNAVAILABLE
    }

    @Volatile
    var immsInstance: Any? = null
        private set

    @Volatile
    private var immsLock: Any? = null

    private var switchToMethod3: Method? = null
    private var setInputMethodMethod3: Method? = null
    private var setInputMethodMethod2: Method? = null
    private var getBindingControllerMethod: Method? = null
    private var getSelectedImeIdMethod: Method? = null

    private val isProgrammaticSwitch = ThreadLocal.withInitial { false }

    @Volatile
    private var isHooked = false

    fun installHooks(
        module: XposedModule,
        classLoader: ClassLoader,
        onContextFound: (Context) -> Unit,
        onExternalSwitch: (imeId: String, userId: Int) -> Unit
    ) {
        if (isHooked) return
        try {
            val immsClass = Class.forName(
                "com.android.server.inputmethod.InputMethodManagerService",
                false,
                classLoader
            )

            immsLock = try {
                Class.forName("com.android.server.inputmethod.ImfLock", false, classLoader)
            } catch (ignored: Throwable) {
                null
            }

            cacheImmsMethods(immsClass)

            for (constructor in immsClass.declaredConstructors) {
                module.hook(constructor)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .intercept { chain ->
                        val result = chain.proceed()
                        val inst = chain.thisObject
                        immsInstance = inst
                        resolveFallbackLockIfNeeded(inst)
                        extractContext(inst, chain.args)?.let(onContextFound)
                        HookUtils.log("Captured IMMS instance (lock=${immsLock?.javaClass?.simpleName ?: immsLock})")
                        result
                    }
            }

            hookExternalSetInputMethod(module, immsClass, onExternalSwitch)
            isHooked = true
        } catch (t: Throwable) {
            HookUtils.logE("Failed to install ImmsBridge hooks", t)
        }
    }

    private fun cacheImmsMethods(immsClass: Class<*>) {
        switchToMethod3 = runCatching {
            immsClass.getDeclaredMethod(
                "switchToInputMethodLocked",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrNull()

        setInputMethodMethod3 = runCatching {
            immsClass.getDeclaredMethod(
                "setInputMethodLocked",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrNull()

        setInputMethodMethod2 = runCatching {
            immsClass.getDeclaredMethod(
                "setInputMethodLocked",
                String::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrNull()

        getBindingControllerMethod = runCatching {
            immsClass.getDeclaredMethod(
                "getInputMethodBindingController",
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    private fun resolveFallbackLockIfNeeded(inst: Any?) {
        if (immsLock != null || inst == null) return
        val methodMapField = HookUtils.findFieldInHierarchy(inst.javaClass, "mMethodMap")
        immsLock = runCatching { methodMapField?.get(inst) }.getOrNull()
    }

    private fun extractContext(inst: Any?, args: List<Any?>): Context? {
        for (arg in args) {
            if (arg is Context) return arg
        }
        if (inst != null) {
            runCatching {
                val field = HookUtils.findFieldInHierarchy(inst.javaClass, "mContext")
                return field?.get(inst) as? Context
            }
        }
        return null
    }

    private fun hookExternalSetInputMethod(
        module: XposedModule,
        immsClass: Class<*>,
        onExternalSwitch: (imeId: String, userId: Int) -> Unit
    ) {
        // Hook the deepest setInputMethodLocked overload so each switch fires once
        val candidates = immsClass.declaredMethods.filter {
            it.name == "setInputMethodLocked" &&
                it.parameterTypes.isNotEmpty() &&
                it.parameterTypes[0] == String::class.java
        }
        val targetMethod = candidates.maxByOrNull { it.parameterCount } ?: return

        module.hook(targetMethod)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .intercept { chain ->
                val result = chain.proceed()
                if (isProgrammaticSwitch.get() != true) {
                    try {
                        val args = chain.args
                        val imeId = args.getOrNull(0) as? String
                        val deviceId = if (args.size >= 4) (args[2] as? Int ?: 0) else 0
                        if (!imeId.isNullOrEmpty() && deviceId == 0) {
                            val userId = if (args.size >= 3) {
                                (args.lastOrNull() as? Int) ?: HookUtils.getCurrentUserId()
                            } else {
                                HookUtils.getCurrentUserId()
                            }
                            onExternalSwitch(imeId, userId)
                        }
                    } catch (t: Throwable) {
                        HookUtils.logE("Error observing external setInputMethodLocked", t)
                    }
                }
                result
            }
        HookUtils.log("Hooked ${immsClass.simpleName}.${targetMethod.name}(${targetMethod.parameterCount} params) for external IME switch tracking")
    }

    fun getSelectedImeInImms(userId: Int): String? {
        val imms = immsInstance ?: return null
        val lock = immsLock ?: return null
        synchronized(lock) {
            val controllerMethod = getBindingControllerMethod
            if (controllerMethod != null) {
                runCatching {
                    val controller = controllerMethod.invoke(imms, userId) ?: return@runCatching null
                    val getter = getSelectedImeIdMethod ?: controller.javaClass
                        .getDeclaredMethod("getSelectedImeId")
                        .apply { isAccessible = true }
                        .also { getSelectedImeIdMethod = it }
                    return getter.invoke(controller) as? String
                }
            }
            return runCatching {
                val field = HookUtils.findFieldInHierarchy(imms.javaClass, "mCurMethodId")
                field?.get(imms) as? String
            }.getOrNull()
        }
    }

    fun switchSynchronously(context: Context?, targetIme: String, userId: Int): SwitchResult {
        val imms = immsInstance ?: return SwitchResult.UNAVAILABLE
        val lock = immsLock ?: return SwitchResult.UNAVAILABLE

        val normalizedTarget = HookUtils.normalizeImeId(targetIme) ?: targetIme
        val token = Binder.clearCallingIdentity()
        try {
            synchronized(lock) {
                val curImmsIme = getSelectedImeInImms(userId)
                val curSecureIme = context?.contentResolver?.let {
                    HookUtils.getSecureStringForUser(it, Settings.Secure.DEFAULT_INPUT_METHOD, userId)
                }
                if (HookUtils.normalizeImeId(curImmsIme) == normalizedTarget &&
                    (curSecureIme == null || HookUtils.normalizeImeId(curSecureIme) == normalizedTarget)
                ) {
                    return SwitchResult.ALREADY_ACTIVE
                }

                isProgrammaticSwitch.set(true)
                try {
                    val switchMethod = switchToMethod3
                    if (switchMethod != null) {
                        val ok = (switchMethod.invoke(imms, normalizedTarget, -1, userId) as? Boolean) ?: false
                        if (!ok) {
                            HookUtils.log("IMMS.switchToInputMethodLocked($normalizedTarget, user=$userId) returned false (IME not enabled)")
                            return SwitchResult.NOT_ENABLED
                        }
                        return SwitchResult.SWITCHED
                    }

                    // Legacy fallback (Android 11-13)
                    if (context != null &&
                        !HookUtils.isImeEnabledInSecureSettings(context.contentResolver, normalizedTarget, userId)
                    ) {
                        return SwitchResult.NOT_ENABLED
                    }
                    val set3 = setInputMethodMethod3
                    if (set3 != null) {
                        set3.invoke(imms, normalizedTarget, -1, userId)
                        return SwitchResult.SWITCHED
                    }
                    val set2 = setInputMethodMethod2
                    if (set2 != null) {
                        set2.invoke(imms, normalizedTarget, -1)
                        return SwitchResult.SWITCHED
                    }
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val cause = e.cause
                    if (cause is IllegalArgumentException || cause is IllegalStateException) {
                        HookUtils.log("IMMS rejected IME switch to $normalizedTarget (user=$userId): ${cause.message}")
                        return SwitchResult.NOT_ENABLED
                    }
                    HookUtils.logE("IMMS switch invocation failed for $normalizedTarget", cause ?: e)
                    return SwitchResult.UNAVAILABLE
                } finally {
                    isProgrammaticSwitch.set(false)
                }
            }
        } catch (t: Throwable) {
            HookUtils.logE("Unexpected error in ImmsBridge.switchSynchronously", t)
        } finally {
            Binder.restoreCallingIdentity(token)
        }
        return SwitchResult.UNAVAILABLE
    }
}

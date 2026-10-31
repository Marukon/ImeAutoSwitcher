package io.github.marukon.imeauto.hook

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import android.util.Log
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

object HookUtils {
    private const val TAG = "ImeAutoHook"
    private var moduleRef: XposedModule? = null

    fun initLogger(module: XposedModule) {
        if (moduleRef == null) {
            moduleRef = module
        }
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        try {
            moduleRef?.log(Log.INFO, TAG, msg)
        } catch (ignored: Throwable) {}
    }

    fun logE(msg: String, throwable: Throwable? = null) {
        Log.e(TAG, msg, throwable)
        try {
            moduleRef?.log(Log.ERROR, TAG, msg, throwable)
        } catch (ignored: Throwable) {}
    }

    fun getSystemContext(): Context? {
        // Method 1: AppGlobals.getInitialApplication()
        try {
            val appGlobalsClass = Class.forName("android.app.AppGlobals")
            val getInitialApplicationMethod = appGlobalsClass.getDeclaredMethod("getInitialApplication")
            val app = getInitialApplicationMethod.invoke(null) as? Context
            if (app != null) return app
        } catch (ignored: Throwable) {}

        // Method 2: ActivityThread.currentActivityThread().getSystemContext()
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod = activityThreadClass.getDeclaredMethod("currentActivityThread")
            currentActivityThreadMethod.isAccessible = true
            val activityThread = currentActivityThreadMethod.invoke(null)
            if (activityThread != null) {
                val getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext")
                getSystemContextMethod.isAccessible = true
                val ctx = getSystemContextMethod.invoke(activityThread) as? Context
                if (ctx != null) return ctx
            }
        } catch (t: Throwable) {
            logE("Failed to get system context via ActivityThread", t)
        }
        return null
    }

    fun getCurrentUserId(): Int {
        return try {
            val amClass = Class.forName("android.app.ActivityManager")
            val method = amClass.getMethod("getCurrentUser")
            (method.invoke(null) as? Int) ?: 0
        } catch (t: Throwable) {
            0
        }
    }

    fun getSecureStringForUser(resolver: ContentResolver, name: String, userId: Int): String? {
        val token = android.os.Binder.clearCallingIdentity()
        return try {
            val method = Settings.Secure::class.java.getMethod(
                "getStringForUser",
                ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(null, resolver, name, userId) as? String
        } catch (t: Throwable) {
            try {
                Settings.Secure.getString(resolver, name)
            } catch (e: Throwable) {
                null
            }
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    fun putSecureStringForUser(resolver: ContentResolver, name: String, value: String, userId: Int): Boolean {
        val token = android.os.Binder.clearCallingIdentity()
        return try {
            val method = Settings.Secure::class.java.getMethod(
                "putStringForUser",
                ContentResolver::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            (method.invoke(null, resolver, name, value, userId) as? Boolean) ?: false
        } catch (t: Throwable) {
            try {
                Settings.Secure.putString(resolver, name, value)
            } catch (e: Throwable) {
                logE("Error writing secure setting", e)
                false
            }
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    fun putGlobalString(resolver: ContentResolver, name: String, value: String): Boolean {
        val token = android.os.Binder.clearCallingIdentity()
        return try {
            Settings.Global.putString(resolver, name, value)
        } catch (t: Throwable) {
            false
        } finally {
            android.os.Binder.restoreCallingIdentity(token)
        }
    }

    fun getPackageNameFromActivityRecord(activityRecord: Any?): String? {
        if (activityRecord == null) return null
        return try {
            val field: Field = activityRecord.javaClass.getField("packageName")
            field.get(activityRecord) as? String
        } catch (e1: Throwable) {
            try {
                val field: Field = activityRecord.javaClass.getDeclaredField("packageName")
                field.isAccessible = true
                field.get(activityRecord) as? String
            } catch (e2: Throwable) {
                try {
                    val compField = activityRecord.javaClass.getDeclaredField("mActivityComponent")
                    compField.isAccessible = true
                    val comp = compField.get(activityRecord)
                    val compPkgMethod = comp?.javaClass?.getMethod("getPackageName")
                    compPkgMethod?.invoke(comp) as? String
                } catch (e3: Throwable) {
                    null
                }
            }
        }
    }

    fun extractUserId(activityRecord: Any?): Int? {
        if (activityRecord == null) return null
        return try {
            val field = findFieldInHierarchy(activityRecord.javaClass, "mUserId")
            field?.getInt(activityRecord)
        } catch (t: Throwable) {
            null
        }
    }

    fun findFieldInHierarchy(clazz: Class<*>, name: String): Field? {
        var current: Class<*>? = clazz
        while (current != null && current != Any::class.java) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                return field
            } catch (ignored: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    fun findFirstMethodNamed(clazz: Class<*>, name: String): Method? {
        for (m in clazz.declaredMethods) {
            if (m.name == name) {
                m.isAccessible = true
                return m
            }
        }
        var current: Class<*>? = clazz.superclass
        while (current != null && current != Any::class.java) {
            for (m in current.declaredMethods) {
                if (m.name == name) {
                    m.isAccessible = true
                    return m
                }
            }
            current = current.superclass
        }
        return null
    }
}

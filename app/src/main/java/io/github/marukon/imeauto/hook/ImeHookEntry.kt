package io.github.marukon.imeauto.hook

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

class ImeHookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        HookUtils.initLogger(this)
        HookUtils.log("ImeHookEntry.onModuleLoaded: process=${param.processName}, isSystemServer=${param.isSystemServer}")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        super.onSystemServerStarting(param)
        HookUtils.initLogger(this)
        HookUtils.log("ImeHookEntry.onSystemServerStarting: initializing SystemServerHook with system ClassLoader")
        SystemServerHook.init(this, param.classLoader)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        super.onPackageLoaded(param)
        HookUtils.initLogger(this)
        if (param.packageName == "android" || param.packageName == "system") {
            HookUtils.log("ImeHookEntry.onPackageLoaded for ${param.packageName}")
            val cl = param.defaultClassLoader
            if (cl != null) {
                SystemServerHook.init(this, cl)
            }
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        super.onPackageReady(param)
        HookUtils.initLogger(this)

        if (param.packageName == "android" || param.packageName == "system") {
            HookUtils.log("ImeHookEntry.onPackageReady for ${param.packageName}")
            SystemServerHook.init(this, param.classLoader)
            return
        }

        // Hook self app to report module active status
        if (param.packageName == "io.github.marukon.imeauto") {
            try {
                val configClass = Class.forName(
                    "io.github.marukon.imeauto.data.ConfigManager",
                    false,
                    param.classLoader
                )
                val isModuleActiveMethod = configClass.getDeclaredMethod("isModuleActive")
                hook(isModuleActiveMethod)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .intercept { true }
                HookUtils.log("Successfully hooked ConfigManager.isModuleActive in self app")
            } catch (t: Throwable) {
                HookUtils.logE("Failed to hook ConfigManager in self app", t)
            }
        }
    }
}

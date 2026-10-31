package io.github.marukon.imeauto

import android.app.Application
import android.util.Log
import io.github.marukon.imeauto.data.ConfigManager
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class ImeAutoApp : Application() {
    override fun onCreate() {
        super.onCreate()

        try {
            XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                override fun onServiceBind(service: XposedService) {
                    Log.i(
                        "ImeAutoApp",
                        "XposedService bound! Framework: ${service.frameworkName} ${service.frameworkVersion}, API: ${service.apiVersion}"
                    )
                    ConfigManager.isFrameworkServiceConnected = true
                    ConfigManager.frameworkName = service.frameworkName ?: "LSPosed"
                    ConfigManager.frameworkVersion = service.frameworkVersion ?: ""
                }

                override fun onServiceDied(service: XposedService) {
                    Log.w("ImeAutoApp", "XposedService died")
                    ConfigManager.isFrameworkServiceConnected = false
                }
            })
        } catch (t: Throwable) {
            Log.e("ImeAutoApp", "Failed to register XposedServiceHelper listener", t)
        }
    }
}

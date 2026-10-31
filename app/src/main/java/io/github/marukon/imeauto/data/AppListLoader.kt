package io.github.marukon.imeauto.data

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import io.github.marukon.imeauto.data.model.AppItem
import io.github.marukon.imeauto.data.model.ImeItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AppListLoader {

    suspend fun loadInstalledImes(context: Context): List<ImeItem> = withContext(Dispatchers.IO) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val pm = context.packageManager
        val defaultIme = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.DEFAULT_INPUT_METHOD
        ) ?: ""

        val list = imm.enabledInputMethodList.map { imi ->
            ImeItem(
                id = imi.id,
                label = imi.loadLabel(pm).toString(),
                packageName = imi.packageName,
                isDefault = imi.id == defaultIme,
                icon = imi.loadIcon(pm)
            )
        }
        list.sortedByDescending { it.isDefault }
    }

    suspend fun loadInstalledApps(
        context: Context,
        appRules: Map<String, String>
    ): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val result = mutableListOf<AppItem>()
        for (info in installedApps) {
            // Skip this configuration module itself
            if (info.packageName == context.packageName) continue

            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isLaunchable = pm.getLaunchIntentForPackage(info.packageName) != null
            val appName = info.loadLabel(pm).toString()
            val icon = info.loadIcon(pm)

            result.add(
                AppItem(
                    name = appName,
                    packageName = info.packageName,
                    isSystem = isSystem,
                    isLaunchable = isLaunchable,
                    assignedImeId = appRules[info.packageName],
                    icon = icon
                )
            )
        }

        // Sort: customized first, then alphabetically by name
        result.sortedWith(
            compareByDescending<AppItem> { it.assignedImeId != null }
                .thenBy { it.name.lowercase() }
        )
    }
}

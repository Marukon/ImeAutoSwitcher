package io.github.marukon.imeauto.data

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

class ConfigContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val callingUid = android.os.Binder.getCallingUid()
        val myUid = android.os.Process.myUid()
        val isSameApp = try {
            android.os.UserHandle.isSameApp(callingUid, myUid)
        } catch (t: Throwable) {
            callingUid == myUid
        }

        if (callingUid != android.os.Process.SYSTEM_UID && !isSameApp && callingUid != 0) {
            throw SecurityException("Unauthorized UID $callingUid attempting to access ConfigContentProvider")
        }

        val ctx = context ?: return null
        return when (method) {
            "getConfig" -> {
                Bundle().apply {
                    putBoolean(ConfigManager.KEY_GLOBAL_ENABLED, ConfigManager.isGlobalEnabled(ctx))
                    putString(ConfigManager.KEY_DEFAULT_IME, ConfigManager.getDefaultIme(ctx))
                    putBoolean(ConfigManager.KEY_AGGRESSIVE_RECHECK, ConfigManager.isAggressiveRecheck(ctx))
                    putString(
                        ConfigManager.KEY_APP_RULES,
                        ConfigManager.getPrefs(ctx).getString(ConfigManager.KEY_APP_RULES, "{}")
                    )
                    putString(
                        ConfigManager.KEY_GROUPS,
                        ConfigManager.getPrefs(ctx).getString(ConfigManager.KEY_GROUPS, "[]")
                    )
                    putString(
                        ConfigManager.SETTINGS_CONFIG_KEY,
                        ConfigManager.buildFullConfigJson(ctx)
                    )
                }
            }
            else -> null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

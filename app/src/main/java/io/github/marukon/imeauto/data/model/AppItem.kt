package io.github.marukon.imeauto.data.model

import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    val isSystem: Boolean,
    val isLaunchable: Boolean,
    val isImeApp: Boolean = false,
    val assignedImeId: String? = null,
    val icon: Drawable? = null
)

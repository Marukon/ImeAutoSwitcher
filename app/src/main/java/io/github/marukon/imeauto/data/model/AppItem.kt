package io.github.marukon.imeauto.data.model

import android.graphics.drawable.Drawable

data class AppItem(
    val name: String,
    val packageName: String,
    val isSystem: Boolean,
    val isLaunchable: Boolean,
    val assignedImeId: String? = null,
    val icon: Drawable? = null
)

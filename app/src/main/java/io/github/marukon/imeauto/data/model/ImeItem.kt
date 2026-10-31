package io.github.marukon.imeauto.data.model

import android.graphics.drawable.Drawable

data class ImeItem(
    val id: String,
    val label: String,
    val packageName: String,
    val isDefault: Boolean = false,
    val icon: Drawable? = null
)

package io.github.marukon.imeauto.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ImeGroup(
    val id: String,
    val name: String,
    val targetImeId: String = "" // Specific IME ID, or "__KEEP__" for keep current
)

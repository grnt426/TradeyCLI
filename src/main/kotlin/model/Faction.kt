package model

import kotlinx.serialization.Serializable

@Serializable
data class Faction(
    val symbol: String,
    val name: String = "",
    val description: String  = "",
    val headquarters: String = "",
    val traits: List<Trait> = emptyList(),
    val isRecruiting: Boolean? = null
)

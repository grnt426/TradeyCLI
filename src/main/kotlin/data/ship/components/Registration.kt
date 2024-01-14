package data.ship.components

import kotlinx.serialization.Serializable

@Serializable
data class Registration(
    val name: String,
    val factionSymbol: String,
    val role: String,
)

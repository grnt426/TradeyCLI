package model.system

import kotlinx.serialization.Serializable

/** A modifier applied to a waypoint, such as STRIPPED or UNSTABLE. Symbols change between resets, so kept as text. */
@Serializable
data class WaypointModifier(
    val symbol: String,
    val name: String = "",
    val description: String = "",
)

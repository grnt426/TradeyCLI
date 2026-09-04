package model.actions

import kotlinx.serialization.Serializable

/** Wear reported after navigation or extraction; the symbol set grows, so it stays text. */
@Serializable
data class ShipConditionEvent(
    val symbol: String,
    val component: String,
    val name: String = "",
    val description: String = "",
)

package model.responsebody

import kotlinx.serialization.Serializable
import model.actions.Extraction
import model.actions.ShipConditionEvent
import model.ship.Cooldown
import model.ship.components.Cargo

/** The siphon endpoint answers like extract: the yield, the cooldown it put the ship on, the hold. */
@Serializable
data class SiphonResponse(
    val siphon: Extraction,
    val cooldown: Cooldown,
    val cargo: Cargo,
    val events: List<ShipConditionEvent> = emptyList(),
)

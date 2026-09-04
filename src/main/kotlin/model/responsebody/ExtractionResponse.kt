package model.responsebody

import kotlinx.serialization.Serializable
import model.actions.Extraction
import model.actions.ShipConditionEvent
import model.ship.Cooldown
import model.ship.components.Cargo
import model.system.WaypointModifier

@Serializable
data class ExtractionResponse(
    val extraction: Extraction,
    val cooldown: Cooldown,
    val cargo: Cargo,

    /** The waypoint's modifiers after this extraction: UNSTABLE, CRITICAL_LIMIT and so on. */
    val modifiers: List<WaypointModifier> = emptyList(),
    val events: List<ShipConditionEvent> = emptyList(),
)

package model.responsebody

import kotlinx.serialization.Serializable
import model.actions.ShipConditionEvent
import model.ship.Navigation
import model.ship.components.Fuel

@Serializable
data class NavigationResponse(
    val nav: Navigation,

    // not all ships have fuel, such as satellites
    val fuel: Fuel? = null,
    val events: List<ShipConditionEvent> = emptyList(),
)

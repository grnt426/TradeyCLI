package model.ship.components

import kotlinx.serialization.Serializable
import model.ship.Ship
import model.ship.ShipRole

@Serializable
data class Registration(
    val name: String,
    val factionSymbol: String,
    val role: ShipRole,
)

fun shortName(ship: Ship): String = ship.registration.name.substringAfterLast("-")
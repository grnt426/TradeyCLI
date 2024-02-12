package model.ship.components

import kotlinx.serialization.Serializable
import model.ship.Ship

@Serializable
data class Registration(
    val name: String,
    val factionSymbol: String,
    val role: String,
)

fun shortName(ship: Ship): String = ship.registration.name.substringAfterLast("-")
package data.ship.components

import data.ship.Ship
import kotlinx.serialization.Serializable

@Serializable
data class Cargo(
    val capacity: Long,
    var units: Long,
    val inventory: List<String>
)

fun cargoFull(ship: Ship): Boolean {
    return ship.cargo.units < ship.cargo.capacity
}

fun cargoNotFull(ship: Ship): Boolean {
    return !cargoFull(ship)
}
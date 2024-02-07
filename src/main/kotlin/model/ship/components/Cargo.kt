package model.ship.components

import model.ship.Ship
import kotlinx.serialization.Serializable

@Serializable
data class Cargo(
    val capacity: Long,
    var units: Long,
    val inventory: List<Inventory>
)

fun cargoFull(ship: Ship): Boolean {
    return ship.cargo.units >= ship.cargo.capacity
}

fun cargoNotFull(ship: Ship): Boolean {
    return !cargoFull(ship)
}
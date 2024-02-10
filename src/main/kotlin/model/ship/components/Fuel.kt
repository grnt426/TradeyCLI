package model.ship.components

import kotlinx.serialization.Serializable

@Serializable
data class Fuel(
    val current: Long,
    val capacity: Long,
    val consumed: FuelConsumed
)

fun hasfuelRatio(fuel: Fuel, ratio: Double): Boolean = fuel.current / fuel.capacity > ratio
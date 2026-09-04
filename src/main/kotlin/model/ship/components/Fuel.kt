package model.ship.components

import kotlinx.serialization.Serializable

@Serializable
data class Fuel(
    val current: Long,
    val capacity: Long,

    /** Optional in the API; absent until a ship has burned fuel. */
    val consumed: FuelConsumed? = null,
)

fun hasfuelRatio(fuel: Fuel, ratio: Double): Boolean = fuel.current / fuel.capacity > ratio

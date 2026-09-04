package model.ship.components

import kotlinx.serialization.Serializable

@Serializable
data class Fuel(
    val current: Long,
    val capacity: Long,

    /** Optional in the API; absent until a ship has burned fuel. */
    val consumed: FuelConsumed? = null,
) {
    /** Fraction of the tank that is full; a ship without a tank counts as full. */
    val ratio: Double get() = if (capacity == 0L) 1.0 else current.toDouble() / capacity
}

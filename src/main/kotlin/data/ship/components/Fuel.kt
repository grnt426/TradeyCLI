package data.ship.components

import kotlinx.serialization.Serializable

@Serializable
data class Fuel(
    val current: Long,
    val capacity: Long,
    val consumed: FuelConsumed
)

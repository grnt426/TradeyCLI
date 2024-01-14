package data.ship.components

import kotlinx.serialization.Serializable

@Serializable
data class FuelConsumed(
    val amount: Long,
    val timestamp: String
)

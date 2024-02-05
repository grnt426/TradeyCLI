package model

import model.ship.ShipType
import kotlinx.serialization.Serializable

@Serializable
data class Transaction(
    val shipSymbol: String? = null,
    val shipType: ShipType? = null,
    val waypointSymbol: String,
    val agentSymbol: String,
    val price: Long,
    val timestamp: String,
)

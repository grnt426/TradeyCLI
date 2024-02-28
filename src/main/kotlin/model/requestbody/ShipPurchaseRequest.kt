package model.requestbody

import kotlinx.serialization.Serializable
import model.ship.ShipType

@Serializable
data class ShipPurchaseRequest(
    val shipType: ShipType,
    val waypointSymbol: String,
)

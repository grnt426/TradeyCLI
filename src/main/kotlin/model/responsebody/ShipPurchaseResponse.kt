package model.responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.market.ShipyardTransaction
import model.ship.Ship

@Serializable
data class ShipPurchaseResponse(
    val agent: Agent,
    val ship: Ship,
    val transaction: ShipyardTransaction,
)

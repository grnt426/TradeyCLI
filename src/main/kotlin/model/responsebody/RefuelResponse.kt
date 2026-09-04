package model.responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.market.MarketTransaction
import model.ship.components.Cargo
import model.ship.components.Fuel

@Serializable
data class RefuelResponse(
    val agent: Agent,
    val fuel: Fuel,
    val transaction: MarketTransaction,
    val cargo: Cargo? = null,
)

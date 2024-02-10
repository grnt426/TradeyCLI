package model.ship.components

import kotlinx.serialization.Serializable
import model.market.TradeSymbol

@Serializable
data class Inventory(
    val symbol: TradeSymbol,
    val name: String,
    val description: String,
    var units: Int,
)

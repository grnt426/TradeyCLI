package model.ship.components

import Symbol
import kotlinx.serialization.Serializable
import model.TradeSymbol

@Serializable
data class Inventory(
    val symbol: TradeSymbol,
    val name: String,
    val description: String,
    val units: Int,
)

package model.ship.components

import kotlinx.serialization.Serializable
import model.market.TradeSymbol
import model.ship.Ship

@Serializable
data class Inventory(
    val symbol: TradeSymbol,
    val name: String,
    val description: String,
    var units: Int,
)

fun inv(ship: Ship): List<Inventory> = ship.cargo.inventory
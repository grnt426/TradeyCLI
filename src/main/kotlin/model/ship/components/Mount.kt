package model.ship.components

import model.TradeSymbol
import model.ship.Requirements
import kotlinx.serialization.Serializable

@Serializable
data class Mount(
    val symbol: String,
    val name: String,
    val description: String,
    val strength: Long,
    val requirements: Requirements,
    val deposits: List<TradeSymbol> = emptyList()
)

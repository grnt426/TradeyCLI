package data.ship.components

import data.TradeSymbol
import data.ship.Requirements
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

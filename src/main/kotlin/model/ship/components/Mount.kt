package model.ship.components

import model.market.TradeSymbol
import model.ship.Requirements
import kotlinx.serialization.Serializable

@Serializable
data class Mount(
    val symbol: MountType,
    val name: String,
    val requirements: Requirements,

    val description: String = "",
    val strength: Long = 0,
    val deposits: List<TradeSymbol> = emptyList()
)

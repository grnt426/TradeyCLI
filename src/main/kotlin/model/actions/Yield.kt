package model.actions

import kotlinx.serialization.Serializable
import model.market.TradeSymbol

@Serializable
data class Yield(
    val symbol: TradeSymbol,
    val units: Long,
)

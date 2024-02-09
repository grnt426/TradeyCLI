package model.market

import kotlinx.serialization.Serializable

@Serializable
data class TradeGood(
    val symbol: TradeSymbol,
    val name: String,
    val description: String,
)

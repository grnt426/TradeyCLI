package model.contract

import model.market.TradeSymbol
import kotlinx.serialization.Serializable

@Serializable
data class DeliverTerm(
    val tradeSymbol: TradeSymbol,
    val destinationSymbol: String,
    val unitsRequired: Long,
    val unitsFulfilled: Long,
)

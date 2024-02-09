package model.market

import kotlinx.serialization.Serializable

@Serializable
data class MarketTransaction(
    val shipSymbol: String,
    val waypointSymbol: String,
    val tradeSymbol: TradeSymbol,
    val type: TransactionType,
    val units: Int,
    val pricePerUnit: Int,
    val totalPrice: Int,
    val timestamp: String,
)

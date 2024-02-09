package model.market

import kotlinx.serialization.Serializable

@Serializable
data class Market(
    val symbol: String,
    val exports: List<TradeGood>,
    val imports: List<TradeGood>,
    val exchange: List<TradeGood>,

    val transactions: List<MarketTransaction> = emptyList(),
    val tradeGoods: List<MarketTradeGood> = emptyList()
)


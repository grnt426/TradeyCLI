package model.responsebody

import kotlinx.serialization.Serializable
import model.market.MarketTradeGood
import model.market.MarketTransaction
import model.market.TradeGood

@Serializable
data class MarketResponse(
    val symbol: String,
    val imports: List<TradeGood>,
    val exports: List<TradeGood>,
    val exchange: List<TradeGood>,

    val tradeGoods: List<MarketTradeGood> = emptyList(),
    val transactions: List<MarketTransaction> = emptyList(),
)

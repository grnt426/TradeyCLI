package model.market

import kotlinx.serialization.Serializable
import model.GameState

@Serializable
data class Market(
    val symbol: String,
    val exports: List<TradeGood>,
    val imports: List<TradeGood>,
    val exchange: List<TradeGood>,

    val transactions: List<MarketTransaction> = emptyList(),
    val tradeGoods: List<MarketTradeGood> = emptyList()
)

fun findMarketForGood(good: TradeSymbol, system: String): Market? {

    // filter by system later
    return GameState.markets.values.firstOrNull { m -> m.imports.find { i -> i.symbol == good } != null }
}
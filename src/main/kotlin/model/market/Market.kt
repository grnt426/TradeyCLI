package model.market

import kotlinx.serialization.Serializable
import model.extension.LastRead

@Serializable
data class Market(
    val symbol: String,
    val exports: List<TradeGood> = emptyList(),
    val imports: List<TradeGood> = emptyList(),
    val exchange: List<TradeGood> = emptyList(),

    val transactions: List<MarketTransaction> = emptyList(),

    /** Present only when one of our ships is at the waypoint. */
    val tradeGoods: List<MarketTradeGood> = emptyList(),
) : LastRead() {

    val hasPrices: Boolean get() = tradeGoods.isNotEmpty()

    fun good(symbol: TradeSymbol): MarketTradeGood? = tradeGoods.firstOrNull { it.symbol == symbol }

    /** Every good this market trades, whether or not prices are known. */
    val tradedSymbols: Set<TradeSymbol> get() = (exports + imports + exchange).map { it.symbol }.toSet()

    fun trades(symbol: TradeSymbol): Boolean = symbol in tradedSymbols

    fun sellPriceOf(symbol: TradeSymbol): Int? = good(symbol)?.sellPrice

    fun typeOf(symbol: TradeSymbol): TradeGoodType? = when {
        imports.any { it.symbol == symbol } -> TradeGoodType.IMPORT
        exports.any { it.symbol == symbol } -> TradeGoodType.EXPORT
        exchange.any { it.symbol == symbol } -> TradeGoodType.EXCHANGE
        else -> null
    }
}

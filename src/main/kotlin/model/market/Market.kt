package model.market

import kotlinx.serialization.Serializable
import model.GameState
import model.ship.components.Inventory

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

fun findGoodsAcceptedHere(goods: List<Inventory>, waypoint: String): List<TradeSymbol> {
    val searchFor = goods.map { i -> i.symbol }
    val market = GameState.markets.values.find { m -> m.symbol == waypoint } ?: return emptyList()
    return intersectTradeGoods(searchFor, market.imports).union(intersectTradeGoods(searchFor, market.exchange)).toList()
}

fun intersectGoods(searchFor: List<TradeSymbol>, searchIn: List<TradeSymbol>): List<TradeSymbol> =
    searchFor.intersect(searchIn.toSet()).toList()

/**
 * This needs a different name otherwise they "have the same JVM signature" ???
 */
fun intersectTradeGoods(searchFor: List<TradeSymbol>, searchIn: List<TradeGood>): List<TradeSymbol> =
    intersectGoods(searchFor, searchIn.map { g -> g.symbol })
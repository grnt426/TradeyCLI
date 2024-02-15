package model.market

import client.SpaceTradersClient
import client.SpaceTradersClient.ignoredFailback
import data.FileWritingQueue
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import model.GameState
import model.api
import model.extension.LastRead
import model.responsebody.MarketResponse
import model.ship.Ship
import model.ship.components.Inventory
import java.io.File

@Serializable
data class Market(
    val symbol: String,
    val exports: MutableList<TradeGood>,
    val imports: MutableList<TradeGood>,
    val exchange: MutableList<TradeGood>,

    val transactions: MutableList<MarketTransaction> = mutableListOf(),
    val tradeGoods: MutableList<MarketTradeGood> = mutableListOf(),

    @Transient var shipAssignedToGetPrices: Ship? = null
) : LastRead()

fun refreshMarket(systemSymbol: String, market: Market) {
    SpaceTradersClient.enqueueRequest<MarketResponse>(
        ::writeMarket, ::ignoredFailback, request {
            url(api("systems/$systemSymbol/waypoints/${market.symbol}/market"))
        }
    )
}

suspend fun writeMarket(resp: MarketResponse) {
    val symbol = resp.data.symbol
    val market = resp.data
    GameState.markets[symbol] = market
    println("Market $symbol updated @ ${market.lastRead}")
    FileWritingQueue.enqueue(File(FileWritingQueue.marketDir(symbol)), market)
}

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
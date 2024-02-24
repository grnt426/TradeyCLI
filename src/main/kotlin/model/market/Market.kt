package model.market

import client.SpaceTradersClient
import client.SpaceTradersClient.ignoredFailback
import data.FileWritingQueue
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.GameState
import model.api
import model.extension.LastRead
import model.ship.Ship
import model.ship.components.Inventory
import model.system.OrbitalNames
import notification.NotificationManager
import java.io.File
import java.time.ZoneId

@Serializable
data class Market(
    var symbol: String,
    var exports: MutableList<TradeGood>,
    var imports: MutableList<TradeGood>,
    var exchange: MutableList<TradeGood>,

    var transactions: MutableList<MarketTransaction> = mutableListOf(),
    var tradeGoods: MutableList<MarketTradeGood> = mutableListOf(),

    @Transient var shipAssignedToGetPrices: Ship? = null
) : LastRead()

fun refreshMarket(systemSymbol: String, market: Market) {
    SpaceTradersClient.enqueueRequest<Market>(
        ::writeMarket, ::ignoredFailback, request {
            url(api("systems/$systemSymbol/waypoints/${market.symbol}/market"))
        }
    )
}

suspend fun writeMarket(resp: Market) {
    val symbol = resp.symbol
    val market = resp
    val orig = GameState.markets[symbol]
    if (orig == null) {
        GameState.markets[symbol] = market
        GameState.marketsBySystem.getOrPut(OrbitalNames.getSectorSystem(symbol)) { mutableListOf() }.add(market)
    } else {
        orig.imports = market.imports
        orig.exports = market.imports
        orig.exchange = market.exchange
        orig.lastRead = market.lastRead

        if (market.tradeGoods != null) orig.tradeGoods = market.tradeGoods
        if (market.transactions != null) orig.transactions = market.transactions
    }
    println("Market $symbol updated @ ${market.lastRead.atZone(ZoneId.systemDefault())}")
    println(Json.encodeToString(market))
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

fun applyMarketTransactionUpdate(transaction: MarketTransaction) {
    val market = marketById(transaction.waypointSymbol)
    if (market == null) {
        NotificationManager.createErrorNotification(
            "${transaction.waypointSymbol} needs an update but not cached", "Bad"
        )
        return
    }
    val orig = market.tradeGoods.first { t -> t.symbol == transaction.tradeSymbol }
    when (transaction.type) {
        TransactionType.PURCHASE -> {
            if (orig.purchasePrice != transaction.pricePerUnit) {
                // update historic price info

            }
        }

        TransactionType.SELL -> TODO()
    }
}

fun marketById(symbol: String): Market? = GameState.markets[symbol]

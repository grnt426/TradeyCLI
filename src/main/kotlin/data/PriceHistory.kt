package data

import model.market.*
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.datetime
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

object PriceHistory : Table() {

    val symbol: Column<String> = varchar("symbol", 256).index()
    val type: Column<String> = varchar("type", 256)
    val volume: Column<Int> = integer("volume")
    val supply: Column<String> = varchar("supply", 256)
    val purchasePrice: Column<Int> = integer("purchase_price")
    val sellPrice: Column<Int> = integer("sell_price")
    val datetime: Column<LocalDateTime> = datetime("datetime")
    val marketLocation: Column<String> = varchar("market_location", 128).index()
    val activity: Column<String?> = varchar("activity", 128).nullable()

    fun saveState(tradeGood: MarketTradeGood, location: String) {
        DbClient.writeQueue.add {
            PriceHistory.insert { p ->
                p[symbol] = tradeGood.symbol.name
                p[type] = tradeGood.type.name
                p[volume] = tradeGood.tradeVolume
                p[supply] = tradeGood.supply.name
                p[purchasePrice] = tradeGood.purchasePrice
                p[activity] = tradeGood.activity?.name
                p[sellPrice] = tradeGood.sellPrice
                p[datetime] = LocalDateTime.now()
                p[marketLocation] = location
            }
        }
    }

    fun getHistoryForGood(symbol: TradeSymbol, location: String): List<PriceTradeHistory> {
        transaction {
            val res = PriceHistory.selectAll()
                .where(PriceHistory.symbol eq symbol.name)
                .where(marketLocation eq location)
            return@transaction res.map { r ->
                PriceTradeHistory(
                    MarketTradeGood(
                        TradeSymbol.valueOf(r[PriceHistory.symbol]),
                        TradeGoodType.valueOf(r[type]),
                        r[volume],
                        SupplyLevel.valueOf(r[supply]),
                        r[purchasePrice],
                        r[sellPrice],
                        r[activity]?.let { ActivityLevel.valueOf(it) }
                    ),
                    r[datetime].toInstant(ZoneOffset.UTC),
                    r[marketLocation]
                )
            }
        }
        return emptyList()
    }
}

data class PriceTradeHistory(
    val marketTradeGood: MarketTradeGood,
    val datetime: Instant,
    val location: String,
)
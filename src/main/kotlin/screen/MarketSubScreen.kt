package screen

import HEADER_COLOR
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.foundation.input.setInput
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import com.varabyte.kotter.runtime.render.RenderScope
import com.varabyte.kotterx.grid.Cols
import com.varabyte.kotterx.grid.GridCharacters
import com.varabyte.kotterx.grid.grid
import makeHeader
import model.GameState
import model.market.Market
import model.market.MarketTradeGood
import model.market.TradeSymbol
import kotlin.math.roundToInt
import kotlin.random.Random

class MarketSubScreen(private val parent: Screen) : SubScreen<RunningScreen.SelectedScreen>(parent) {

    private val self = this
    override fun MainRenderScope.render() {

        val random = Random(0)
        val marketData = (0..12).map { i ->
            val p = 1500 - (i * 27 + random.nextInt(7))
            MarketDataPoint(p, p - 230 - random.nextInt(3), 20)
        }.toMutableList()
        with(marketData) {
            addAll(
                (0..8).map { i ->
                    val p = marketData.last().buyPrice + (i * 18 + random.nextInt(5))
                    MarketDataPoint(p, p - 230 - random.nextInt(3), 30)
                }
            )
            addAll(
                (0..4).map { i ->
                    val p = marketData.last().buyPrice - (i * 13 + random.nextInt(5))
                    MarketDataPoint(p, p - 230 - random.nextInt(3), 30)
                }
            )
            addAll(
                (0..8).map { i ->
                    val p = marketData.last().buyPrice + (i * 21 + random.nextInt(5))
                    MarketDataPoint(p, p - 230 - random.nextInt(3), 30)
                }
            )
            addAll(
                (0..4).map { i ->
                    val p = marketData.last().buyPrice - (i * 13 + random.nextInt(5))
                    MarketDataPoint(p, p - 230 - random.nextInt(3), 30)
                }
            )
            addAll(
                (0..8).map { i ->
                    val p = marketData.last().buyPrice + (i * 21 + random.nextInt(5))
                    MarketDataPoint(p, p - 230 - random.nextInt(3), 30)
                }
            )
            addAll(
                (0..30).map { i ->
                    val p = marketData.last().buyPrice - (i * 30 + random.nextInt(9))
                    MarketDataPoint(p, p - 230 - random.nextInt(3), if (i < 15) 30 else 25)
                }
            )
            addAll(
                (0..20).map { i ->
                    val p = marketData.last().buyPrice + (i * 5 + random.nextInt(3))
                    MarketDataPoint(p, p - 230 - random.nextInt(3), 25)
                }
            )
        }

        val charHeight = 3 * 8 // 3 rows * 8 characters a row

        grid(
            Cols.uniform(ConsoleSubScreen.columns, GameState.profData.termWidth / ConsoleSubScreen.columns),
            characters = GridCharacters.CURVED
        ) {

            // row 1
            cell(colSpan = ConsoleSubScreen.columns - 2) {
                try {
                    rgb(HEADER_COLOR.rgb) { makeHeader("Market History Data", ConsoleSubScreen.columns - 2) }
                    val yHeight = marketData.maxOf { m -> m.buyPrice } * 1.10
                    val normalizedCharValue = yHeight / charHeight
                    val volumeNormalizedCharValue = marketData.maxOf { m -> m.volume } * 1.50 / charHeight
                    val EMPTY_RENDER = { text(" ") }
                    val BUY_LINE = { blue { text("█") } }
                    val UP_R_CORNER = "▜"
                    val BOT_R_CORNER = "▟"
                    val UP_L_CORNER = "▛"
                    val BOT_L_CORNER = "▙"
                    val SELL_LINE = { yellow { text("█") } }
                    val VOLUME_LINE = { green { text("█") } }


                    val ST = ""

                    val map = MutableList(charHeight) { // number of rows
                        MutableList(marketData.size) { // number of columns
                            EMPTY_RENDER
                        }
                    }

                    var prevBuyPrice = 0
                    var prevSellPrice = 0

                    marketData.forEachIndexed { i, d ->
                        val vPos = convertToCharPos(charHeight, volumeNormalizedCharValue, d.volume)
                        map[vPos][i] = VOLUME_LINE
                        val bPos = convertToCharPos(charHeight, normalizedCharValue, d.buyPrice)
                        map[bPos][i] = BUY_LINE
                        if (i != 0) {
                            if (prevBuyPrice > bPos) {
                                map[bPos][i - 1] = { blue { text(BOT_R_CORNER) } }
                                map[bPos + 1][i] = { blue { text(UP_L_CORNER) } }
                            } else if (prevBuyPrice < bPos) {
                                map[bPos - 1][i] = { blue { text(BOT_L_CORNER) } }
                                map[bPos][i - 1] = { blue { text(UP_R_CORNER) } }
                            }
                        }
                        prevBuyPrice = bPos
                        val sPos = convertToCharPos(charHeight, normalizedCharValue, d.sellPrice)
                        map[sPos][i] = SELL_LINE
                        if (i != 0) {
                            if (prevSellPrice > sPos) {
                                map[sPos][i - 1] = { yellow { text(BOT_R_CORNER) } }
                                map[sPos + 1][i] = { yellow { text(UP_L_CORNER) } }
                            } else if (prevSellPrice < sPos) {
                                map[sPos - 1][i] = { yellow { text(BOT_L_CORNER) } }
                                map[sPos][i - 1] = { yellow { text(UP_R_CORNER) } }
                            }
                        }

                        prevSellPrice = sPos
                    }

                    val moneyLength = marketData.maxOf { m -> m.buyPrice }.toString().length + 1
                    val volumeLength = marketData.maxOf { m -> m.volume }.toString().length

                    map.forEachIndexed { i, row ->
                        when (i) {
                            0 -> text("$${(marketData.maxOf { m -> m.buyPrice } * 1.10).toInt()}")
                            map.lastIndex -> {
                                repeat(moneyLength - 2) { text(" ") }
                                text("$0")
                            }

                            else -> repeat(moneyLength) { text(" ") }
                        }
                        text("|")
                        row.forEach { c ->
                            c()
                        }
                        text("|")
                        when (i) {
                            0 -> text((marketData.maxOf { m -> m.volume } * 1.50).toInt().toString())
                            map.lastIndex -> text("0")
                        }
                        textLine()
                    }
                } catch (e: Exception) {
                    println(e)
                    e.printStackTrace()
                }

                blue { text("  Buy") }
                yellow { text(" Sell") }
                repeat(marketData.size - 6) { text(" ") }
                green { text("Volume ") }
            }

            cell(colSpan = 2) {
                rgb(HEADER_COLOR.rgb) { makeHeader("Good Data", 2) }
            }

            // row 2
            cell {
                textLine("Whatever")
            }
        }
    }

    private fun convertToCharPos(height: Int, normalizedCharValue: Double, value: Int): Int =
        (height - (value / normalizedCharValue).roundToInt()).coerceIn(0..<height)

    fun RenderScope.tradeOpportunities() {
        val markets = GameState.markets.values
        val opportunities = mutableListOf<TradeOpportunity>()
        TradeSymbol.entries.forEach { t ->
            val exports = markets
                .filter { m -> m.exports.firstOrNull { g -> g.symbol == t } != null }
                .map { m ->
                    Pair(m, m.tradeGoods.first { mtg -> mtg.symbol == t })
                }
                .sortedBy { p -> p.second.purchasePrice }

            val imports = markets
                .filter { m -> m.imports.firstOrNull { g -> g.symbol == t } != null }
                .map { m ->
                    Pair(m, m.tradeGoods.first { mtg -> mtg.symbol == t })
                }
                .sortedBy { p -> p.second.purchasePrice }
                .reversed()

            if (exports.isNotEmpty() && imports.isNotEmpty()) {
                val ex = exports.first()
                val im = imports.first()
//                textLine("$t E:${ex.second.purchasePrice} I:${im.second.sellPrice}")
//                textLine("${ex.second.supply} ${ex.second.activity}")
                opportunities.add(TradeOpportunity(ex.first, im.first, ex.second, im.second))
            }
        }

        val profit = opportunities.sortedBy { o ->
            o.toGood.sellPrice - o.fromGood.purchasePrice
        }
        profit.forEach { o ->
            val margin = o.toGood.sellPrice - o.fromGood.purchasePrice
            if (margin in 401..999) {
                textLine("${o.toGood.symbol} $$margin")
                textLine("FS: ${o.fromGood.supply} FA: ${o.fromGood.activity} TA: ${o.toGood.activity}")
                textLine("From: ${o.fromMarket.symbol} To: ${o.toMarket.symbol}")
            }
        }
    }

    override fun OnInputEnteredScope.onInput(runScope: RunScope): RunningScreen.SelectedScreen {
        if (parent.isActiveSubScreen(self)) {
            return parent.getActiveSelectedScreen() as RunningScreen.SelectedScreen
        }
        return parent.getActiveSelectedScreen() as RunningScreen.SelectedScreen
    }

    override fun OnKeyPressedScope.onKeyPressed(runScope: RunScope): RunningScreen.SelectedScreen {
        if (parent.isActiveSubScreen(self)) {
            when (key) {
                Keys.TICK -> {
                    runScope.setInput("")
                    return RunningScreen.SelectedScreen.CONSOLE
                }
            }
            return parent.getActiveSelectedScreen() as RunningScreen.SelectedScreen
        }
        return parent.getActiveSelectedScreen() as RunningScreen.SelectedScreen
    }

    data class TradeOpportunity(
        val fromMarket: Market, val toMarket: Market,
        val fromGood: MarketTradeGood, val toGood: MarketTradeGood,
    )
}

data class MarketDataPoint(
    val buyPrice: Int,
    val sellPrice: Int,
    val volume: Int,
)
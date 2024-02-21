package screen

import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.foundation.input.setInput
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import model.GameState
import model.market.Market
import model.market.MarketTradeGood
import model.market.TradeSymbol

class MarketSubScreen(private val parent: Screen) : SubScreen<RunningScreen.SelectedScreen>(parent) {

    private val self = this
    override fun MainRenderScope.render() {
        textLine("Market Screen")
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
            if (margin > 100) {
                textLine("${o.toGood.symbol} $$margin")
                textLine("FS: ${o.fromGood.supply} FA: ${o.fromGood.activity} TA: ${o.toGood.activity}")
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
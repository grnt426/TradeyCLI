package bridge.views

import behaviour.decisions.TradePlan
import behaviour.decisions.Trading
import bridge.BridgeModel
import bridge.Format
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Rgb
import bridge.fx.Starfield
import bridge.glyphs.Palette
import bridge.scene.Grid
import bridge.scene.Line
import bridge.scene.LineChart
import bridge.scene.Series
import bridge.scene.Table
import bridge.scene.TextBlock
import knowledge.MarketHealth
import model.market.ActivityLevel
import model.market.Market
import model.market.SupplyLevel
import model.market.TradeGoodType
import model.market.TradeSymbol
import java.time.Duration
import java.time.Instant

/**
 * The analysis screen: every good against every market in the system as a grid of prices
 * coloured by supply, the selected good's price history across the markets that trade it, the
 * routes the trader would rank right now, and the producers that are starving and what would
 * feed them.
 */
class MarketsView : WidgetView() {
    override val title = "Markets"
    private var model: BridgeModel? = null
    private val stars = Starfield(seed = 13, density = 0.006)
    private var selectedGood: TradeSymbol? = null

    private val grid = Grid(labelWidth = 18, cellWidth = 6, onCursor = { rowKey, _ -> selectedGood = runCatching { TradeSymbol.valueOf(rowKey) }.getOrNull() })
    private val routes = Table(
        columns = listOf(
            Table.Column("good", 16),
            Table.Column("buy at", 12),
            Table.Column("price", 6, alignRight = true),
            Table.Column("sell at", 12),
            Table.Column("price", 6, alignRight = true),
            Table.Column("units", 5, alignRight = true),
            Table.Column("profit", 8, alignRight = true),
            Table.Column("cr/h", 7, alignRight = true),
            Table.Column("score", 6, alignRight = true),
            Table.Column("health"),
        ),
        onSelect = { row -> row?.let { r -> grid.moveTo(r.key.substringBefore('|'), null) } },
    )
    private val starving = TextBlock(lines = { starvingLines() }, empty = "no starving producers")

    private var rankedAt: Instant? = null
    private var ranked: List<TradePlan> = emptyList()

    override fun paint(p: Painter, model: BridgeModel, t: Double) {
        this.model = model
        beginFrame()
        stars.paint(p, t)
        val snap = model.snapshot()
        val now = model.now()
        val sys = snap.hqSystem
        val markets = sys?.let { snap.marketsIn(it) } ?: emptyList()

        val rightW = (p.width * 0.42).toInt().coerceIn(50, 90)
        val (left, right) = Rect(0, 0, p.width, p.height).cols(Len.weight(), Len.fixed(rightW))
        val (chartRect, routesRect, starvingRect) = right.rows(Len.fixed(13), Len.weight(), Len.fixed(if (p.height >= 36) 10 else 0))

        gridData(markets)
        val gridPanel = p.panel(left, "Goods × markets · ${sys ?: "?"}", focus === grid, hint = "arrows · click · exports show the buy price, imports the sell price")
        place(grid, gridPanel.sub(Rect(0, 0, gridPanel.width, gridPanel.height - 1)), t)
        legend(gridPanel)

        val good = selectedGood
        val chart = p.panel(chartRect, good?.let { "$it · price history" } ?: "Price history", hint = "exporters' buy · importers' sell")
        if (good != null) LineChart.paint(chart, priceSeries(good, markets, model), now, model.dots)
        else chart.text(0, 0, "move the cursor onto a good", Palette.textDim)

        refreshRoutes(model)
        val ship = snap.ships.values.filter { it.cargo.capacity > 0 }.maxByOrNull { it.cargo.capacity }
        routes.setRows(ranked.take(60).map { r ->
            Table.Row(
                "${r.good.name}|${r.source.symbol}|${r.destination.symbol}",
                listOf(
                    r.good.name, r.source.symbol.substringAfterLast('-').let { "→ $it" }, r.buyPrice.toString(),
                    r.destination.symbol.substringAfterLast('-').let { "→ $it" }, r.sellPrice.toString(), r.units.toString(),
                    Format.compact(r.profit), r.creditsPerHour.toInt().toString(), r.score.toInt().toString(), r.health,
                ),
                when { r.score <= 0 -> Palette.textDim; r.profit < 0 -> Palette.bad; else -> Palette.text },
            )
        })
        place(routes, p.panel(routesRect, "Routes" + (ship?.let { " · for ${it.symbol} (${it.cargo.capacity} hold)" } ?: ""), focus === routes, hint = "↑↓ · Enter shows the good"), t)
        if (starvingRect.h > 0) place(starving, p.panel(starvingRect, "Starving producers"), t)
        endFrame(listOf(grid, routes))
    }

    private fun legend(p: Painter) {
        val y = p.height - 1
        var x = 0
        for ((label, colour) in listOf("scarce" to Palette.bad, "limited" to Palette.warn, "moderate" to Palette.text, "high" to Palette.good, "abundant" to Palette.good.scale(1.3))) {
            p.put(x, y, '■', colour)
            p.text(x + 2, y, label, Palette.textDim)
            x += label.length + 4
        }
        p.put(x, y, ' ', Palette.text, Palette.bad.scale(0.5))
        p.text(x + 2, y, "restricted", Palette.textDim)
        x += 13
        p.text(x, y, "blank = not traded  ? = prices not read", Palette.textDim)
    }

    private fun gridData(markets: List<Market>) {
        val goods = markets.flatMap { m -> m.tradedSymbols }.distinct()
            .sortedWith(compareByDescending<TradeSymbol> { g -> markets.count { m -> m.typeOf(g) == TradeGoodType.EXPORT } }.thenBy { it.name })
        val cells = HashMap<Pair<Int, Int>, Grid.Cell>()
        goods.forEachIndexed { r, good ->
            markets.forEachIndexed { c, m ->
                if (!m.trades(good)) return@forEachIndexed
                val type = m.typeOf(good)
                val g = m.good(good)
                if (g == null) {
                    cells[r to c] = Grid.Cell("?", Palette.textDim)
                    return@forEachIndexed
                }
                val price = if (type == TradeGoodType.EXPORT) g.purchasePrice else g.sellPrice
                val base = when (type) {
                    TradeGoodType.EXPORT -> Palette.good
                    TradeGoodType.IMPORT -> Palette.info
                    else -> Palette.text
                }
                val fg = when (g.supply) {
                    SupplyLevel.SCARCE -> Palette.bad
                    SupplyLevel.LIMITED -> Palette.warn
                    SupplyLevel.MODERATE -> base.mix(Palette.text, 0.4)
                    SupplyLevel.HIGH -> base
                    SupplyLevel.ABUNDANT -> base.scale(1.3)
                }
                val restricted = g.activity == ActivityLevel.RESTRICTED
                cells[r to c] = Grid.Cell(Format.compact(price.toLong()), fg, if (restricted) Palette.bad.scale(0.5) else null, bold = type == TradeGoodType.EXPORT)
            }
        }
        grid.setData(goods.map { it.name }, goods.map { it.name }, markets.map { it.symbol }, markets.map { it.symbol.substringAfterLast('-') }, cells)
        if (selectedGood == null) selectedGood = grid.cursorRowKey?.let { runCatching { TradeSymbol.valueOf(it) }.getOrNull() }
    }

    private fun priceSeries(good: TradeSymbol, markets: List<Market>, model: BridgeModel): List<Series> {
        val out = ArrayList<Series>()
        var hue = 0
        for (m in markets.filter { it.trades(good) }) {
            val type = m.typeOf(good)
            val history = model.prices(m.symbol).filter { it.good.symbol == good }
            if (history.isEmpty()) continue
            val points = history.map { it.observedAt to (if (type == TradeGoodType.EXPORT) it.good.purchasePrice else it.good.sellPrice).toDouble() }
            val label = "${m.symbol.substringAfterLast('-')} ${if (type == TradeGoodType.EXPORT) "buy" else "sell"}"
            out += Series(label, LineChart.HUES[hue++ % LineChart.HUES.size], points)
        }
        return out
    }

    /** The trader's ranking is a real computation over every pair of markets: refreshed every quarter minute. */
    private fun refreshRoutes(model: BridgeModel) {
        val now = model.now()
        val last = rankedAt
        if (last != null && Duration.between(last, now).seconds < 15) return
        rankedAt = now
        val snap = model.snapshot()
        val ship = snap.ships.values.filter { it.cargo.capacity > 0 }.maxByOrNull { it.cargo.capacity } ?: return
        ranked = runCatching { Trading.rank(snap, ship, now) }.getOrElse { emptyList() }
    }

    private fun starvingLines(): List<Line> {
        val m = model ?: return emptyList()
        val snap = m.snapshot()
        val sys = snap.hqSystem ?: return emptyList()
        val markets = snap.marketsIn(sys)
        val lines = mutableListOf<Line>()
        for (market in markets) {
            for (g in market.tradeGoods.filter { it.type == TradeGoodType.EXPORT && MarketHealth.starved(it) }) {
                lines += Line("${g.symbol.name} at ${market.symbol.substringAfterLast('-')}: ${MarketHealth.explain(g)}", Palette.warn)
                for (input in MarketHealth.starvedInputs(market, g.symbol)) {
                    val source = markets.filter { it.symbol != market.symbol }
                        .mapNotNull { s -> s.good(input.symbol)?.let { s to it } }
                        .filter { (_, o) -> !MarketHealth.starved(o) }
                        .minByOrNull { (_, o) -> o.purchasePrice }
                    lines += Line(
                        "  needs ${input.symbol.name} (${MarketHealth.describe(input)}, pays ${input.sellPrice})" +
                            (source?.let { (s, o) -> " ← ${s.symbol.substringAfterLast('-')} at ${o.purchasePrice}" } ?: " ← no healthy source"),
                        Palette.text,
                    )
                }
            }
        }
        return lines
    }
}

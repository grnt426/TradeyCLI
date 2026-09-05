package bridge.views

import bridge.BridgeModel
import bridge.Format
import bridge.canvas.Attr
import bridge.canvas.Glyphs
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Rgb
import bridge.fx.Art
import bridge.fx.Starfield
import bridge.glyphs.Atlas
import bridge.glyphs.Palette
import bridge.scene.Keys
import bridge.scene.Table
import bridge.scene.TextBlock
import bridge.tty.Input
import model.market.ActivityLevel
import model.market.SupplyLevel
import model.market.TradeGoodType
import model.system.Waypoint
import java.time.Instant

/**
 * One waypoint up close: its portrait, what it is, its market with price trends, and either the
 * construction bill or what our ships did there lately. Left and Right step through the system.
 */
class WaypointView : WidgetView() {
    override val title = "Waypoint"
    private var model: BridgeModel? = null
    private val stars = Starfield(seed = 5, density = 0.01)

    private val market = Table(
        columns = listOf(
            Table.Column("good", 18),
            Table.Column("type", 8),
            Table.Column("supply", 8),
            Table.Column("activity", 10),
            Table.Column("buy", 7, alignRight = true),
            Table.Column("sell", 7, alignRight = true),
            Table.Column("vol", 4, alignRight = true),
            Table.Column("trend (last reads)"),
        ),
    )

    private fun current(): Waypoint? {
        val m = model ?: return null
        val snap = m.snapshot()
        m.selectedWaypoint?.let { snap.waypoints[it] }?.let { return it }
        val hq = snap.hqSystem ?: return null
        val wps = snap.waypointsIn(hq)
        return (wps.firstOrNull { it.hasMarket } ?: wps.firstOrNull())?.also { m.selectedWaypoint = it.symbol }
    }

    override fun paint(p: Painter, model: BridgeModel, t: Double) {
        this.model = model
        beginFrame()
        stars.paint(p, t)
        val snap = model.snapshot()
        val now = model.now()
        val wp = current()
        if (wp == null) {
            p.panel(Rect(0, 0, p.width, p.height), "Waypoint").text(0, 0, "no waypoints loaded yet", Palette.textDim)
            endFrame(emptyList())
            return
        }
        val artW = (p.width / 2).coerceIn(30, 66)
        val (left, right) = Rect(0, 0, p.width, p.height).cols(Len.fixed(artW), Len.weight())
        val (factsRect, marketRect, bottomRect) = right.rows(Len.fixed(10), Len.weight(), Len.fixed(if (p.height >= 34) 9 else 0))

        val glyph = Atlas.waypoint(wp.type)
        val art = p.panel(left, "${wp.symbol} · ${wp.type.name.lowercase().replace('_', ' ')}", hint = "← → step through the system")
        val gateFraction = snap.constructionBill?.let { bill -> bill.sumOf { it.fulfilled }.toDouble() / bill.sumOf { it.required }.coerceAtLeast(1) } ?: 0.0
        Art.waypoint(art.sub(Rect(0, 0, art.width, art.height - 1)), wp, t, Art.Extra(gateFraction))
        traitChips(art, wp)

        facts(p.panel(factsRect, "About", hint = "${wp.x}, ${wp.y}"), wp, model)

        val m = snap.markets[wp.symbol]
        val title = if (m == null) "Market" else "Market · ${m.tradeGoods.size} goods" + (if (m.hasPrices) " · read ${Format.age(m.lastRead, now)} ago" else " · prices not read")
        market.setRows(marketRows(wp, model))
        place(market, p.panel(marketRect, title, focus === market, hint = if (m == null) "" else "↑↓ scroll"), t)

        if (bottomRect.h > 0) {
            if (wp.isUnderConstruction && snap.constructionBill != null) bill(p.panel(bottomRect, "Construction bill"), snap.constructionBill!!)
            else transactions(p.panel(bottomRect, "Our trades here"), wp, model)
        }
        endFrame(listOf(market))
    }

    private fun traitChips(p: Painter, wp: Waypoint) {
        val y = p.height - 1
        var x = 0
        for (trait in wp.traits) {
            val chip = " ${trait.name.lowercase()} "
            if (x + chip.length > p.width) break
            p.text(x, y, chip, Palette.text, Palette.panel)
            x += chip.length + 1
        }
    }

    private fun facts(p: Painter, wp: Waypoint, model: BridgeModel) {
        val snap = model.snapshot()
        val now = model.now()
        var y = 0
        val glyph = Atlas.waypoint(wp.type)
        p.put(0, y, glyph.ch, glyph.colour, null, Attr.BOLD)
        p.text(2, y++, wp.symbol, Palette.textBright, null, Attr.BOLD)
        wp.orbits?.let { p.text(0, y++, "orbits $it", Palette.textDim) }
        if (wp.orbitals.isNotEmpty()) p.text(0, y++, "orbitals: ${wp.orbitals.joinToString(" ") { it.symbol.substringAfterLast('-') }}".take(p.width), Palette.textDim)
        wp.faction?.let { p.text(0, y++, "faction ${it.symbol}", Palette.textDim) }
        if (wp.modifiers.isNotEmpty()) p.text(0, y++, "modifiers: ${wp.modifiers.joinToString { it.symbol }}".take(p.width), Palette.warn)
        val flags = buildList {
            if (wp.hasMarket) add("market")
            if (wp.hasShipyard) add("shipyard")
            if (wp.isUnderConstruction) add("under construction")
            if (wp.isMineable) add("minable")
            if (wp.isSiphonable) add("siphonable")
            if (!wp.isCharted) add("uncharted")
        }
        if (flags.isNotEmpty()) p.text(0, y++, flags.joinToString(" · "), Palette.info)
        snap.shipyards[wp.symbol]?.let { yard ->
            p.text(0, y++, "shipyard sells: ${yard.shipTypes.joinToString(", ") { it.type.name.removePrefix("SHIP_").lowercase().replace('_', ' ') }}".take(p.width), Palette.text)
        }
        val here = snap.ships.values.filter { it.nav.waypointSymbol == wp.symbol && !it.nav.inTransitAt(now) }.sortedBy { it.symbol }
        if (here.isNotEmpty()) {
            p.text(0, y, "here:", Palette.textDim)
            var x = 6
            for (s in here) {
                val label = s.symbol.substringAfterLast('-')
                if (x + label.length + 2 > p.width) break
                p.put(x, y, '◆', Atlas.role(s.registration.role))
                p.text(x + 1, y, label, Palette.text)
                x += label.length + 3
            }
            y++
        }
        val inbound = snap.ships.values.filter { it.nav.inTransitAt(now) && it.nav.route.destination.symbol == wp.symbol }
        if (inbound.isNotEmpty()) p.text(0, y++, "inbound: ${inbound.joinToString(", ") { "${it.symbol.substringAfterLast('-')} in ${Format.clock(java.time.Duration.between(now, it.nav.route.arrival))}" }}".take(p.width), Palette.info)
    }

    private fun marketRows(wp: Waypoint, model: BridgeModel): List<Table.Row> {
        val m = model.snapshot().markets[wp.symbol] ?: return emptyList()
        val history = model.prices(wp.symbol).groupBy { it.good.symbol }
        val order = mapOf(TradeGoodType.EXPORT to 0, TradeGoodType.IMPORT to 1, TradeGoodType.EXCHANGE to 2)
        if (!m.hasPrices) {
            return (m.exports.map { it.symbol to TradeGoodType.EXPORT } + m.imports.map { it.symbol to TradeGoodType.IMPORT } + m.exchange.map { it.symbol to TradeGoodType.EXCHANGE })
                .map { (sym, type) -> Table.Row(sym.name, listOf(sym.name, type.name.lowercase(), "", "", "", "", "", ""), typeTone(type)) }
        }
        return m.tradeGoods.sortedWith(compareBy({ order[it.type] }, { it.symbol.name })).map { g ->
            val series = history[g.symbol].orEmpty().map { if (g.type == TradeGoodType.EXPORT) it.good.purchasePrice else it.good.sellPrice }
            val tone = when {
                g.activity == ActivityLevel.RESTRICTED -> Palette.bad
                g.supply == SupplyLevel.SCARCE && g.type == TradeGoodType.IMPORT -> Palette.warn
                else -> typeTone(g.type)
            }
            Table.Row(
                g.symbol.name,
                listOf(
                    g.symbol.name, g.type.name.lowercase(), g.supply.name.lowercase(), g.activity?.name?.lowercase() ?: "",
                    g.purchasePrice.toString(), g.sellPrice.toString(), g.tradeVolume.toString(), sparkline(series, 24),
                ),
                tone,
            )
        }
    }

    private fun typeTone(type: TradeGoodType): Rgb = when (type) {
        TradeGoodType.EXPORT -> Palette.good
        TradeGoodType.IMPORT -> Palette.info
        TradeGoodType.EXCHANGE -> Palette.text
    }

    private fun bill(p: Painter, bill: List<model.ConstructionMaterial>) {
        bill.forEachIndexed { i, mtl ->
            if (i >= p.height) return
            val done = mtl.fulfilled >= mtl.required
            p.text(0, i, mtl.tradeSymbol.name.take(20).padEnd(20), if (done) Palette.good else Palette.text)
            p.gauge(21, i, (p.width - 21 - 14).coerceAtLeast(4), if (mtl.required == 0L) 1.0 else mtl.fulfilled.toDouble() / mtl.required, if (done) Palette.good else Palette.accent)
            p.textRight(p.width, i, "${mtl.fulfilled}/${mtl.required}", Palette.textDim)
        }
    }

    private fun transactions(p: Painter, wp: Waypoint, model: BridgeModel) {
        val now = model.now()
        val ours = model.snapshot().recentTransactions.filter { it.waypointSymbol == wp.symbol }.sortedByDescending { it.timestamp }
        if (ours.isEmpty()) {
            p.text(0, 0, "none in the last hours", Palette.textDim)
            return
        }
        ours.take(p.height).forEachIndexed { i, tx ->
            val at = runCatching { Instant.parse(tx.timestamp) }.getOrNull()
            val sale = tx.type.name == "SELL"
            p.text(0, i, (at?.let { Format.age(it, now) } ?: "").padStart(4), Palette.textDim)
            p.text(5, i, tx.shipSymbol.substringAfterLast('-').padStart(2), Palette.text)
            p.text(8, i, "${if (sale) "sold" else "bought"} ${tx.units} ${tx.tradeSymbol.name}".take(p.width - 20), if (sale) Palette.good else Palette.warn)
            p.textRight(p.width, i, (if (sale) "+" else "-") + Format.credits(tx.totalPrice.toLong()), if (sale) Palette.good else Palette.warn)
        }
    }

    override fun onInput(input: Input, model: BridgeModel): Boolean {
        if (input is Input.Key) {
            val key = Keys.normalise(input.key)
            if (key == "ArrowLeft" || key == "ArrowRight") {
                val snap = model.snapshot()
                val wp = current() ?: return true
                val all = snap.waypointsIn(wp.systemSymbol).map { it.symbol }
                val i = all.indexOf(wp.symbol)
                if (all.isNotEmpty()) model.selectedWaypoint = all[((i + if (key == "ArrowRight") 1 else -1) % all.size + all.size) % all.size]
                return true
            }
        }
        return super.onInput(input, model)
    }

    companion object {
        /** The last [width] values as a one-row sparkline, scaled to their own range. */
        fun sparkline(values: List<Int>, width: Int): String {
            if (values.isEmpty()) return ""
            val shown = values.takeLast(width)
            val min = shown.min()
            val max = shown.max()
            val span = (max - min).coerceAtLeast(1)
            return shown.joinToString("") { v ->
                val level = if (max == min) 4 else ((v - min) * (Glyphs.RAMP_V.length - 2) / span) + 1
                Glyphs.RAMP_V[level.coerceIn(1, Glyphs.RAMP_V.length - 1)].toString()
            }
        }
    }
}

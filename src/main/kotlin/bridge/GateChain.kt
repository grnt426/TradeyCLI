package bridge

import behaviour.decisions.Summary
import bridge.canvas.Rgb
import bridge.glyphs.Palette
import bridge.scene.Line
import engine.Snapshot
import knowledge.ImportMap
import knowledge.MarketHealth
import model.market.ActivityLevel
import model.market.Market
import model.market.MarketTradeGood
import model.market.TradeGoodType
import model.market.TradeSymbol
import java.time.Duration
import java.time.Instant

/**
 * The gate's production chain as the console shows it: the goods the bill still needs and their
 * inputs down to the ores, the home markets that export them, and the health of those listings,
 * now and an hour ago. One number and a direction for the bridge; the list for the economy screen.
 */
object GateChain {
    /** Every good in the chain: the outstanding bill materials and their inputs, four levels down. */
    fun goods(snap: Snapshot): Set<TradeSymbol> {
        val roots = snap.constructionBill?.filter { it.fulfilled < it.required }?.map { it.tradeSymbol } ?: return emptySet()
        val goods = linkedSetOf<TradeSymbol>()
        fun walk(g: TradeSymbol, depth: Int) { if (goods.add(g) && depth < 4) ImportMap.inputs[g].orEmpty().forEach { walk(it, depth + 1) } }
        roots.forEach { walk(it, 0) }
        return goods
    }

    /** The home markets exporting a chain good, with the listing. */
    fun producers(snap: Snapshot): List<Triple<Market, TradeSymbol, MarketTradeGood>> {
        val home = snap.hqSystem ?: return emptyList()
        val goods = goods(snap)
        return snap.marketsIn(home).flatMap { m -> goods.filter { m.typeOf(it) == TradeGoodType.EXPORT }.mapNotNull { g -> m.good(g)?.let { Triple(m, g, it) } } }
    }

    /**
     * The chain's health as it stood at [at]: the average score of each chain listing's last
     * observation before then, from the console's price history. Null until there is history.
     */
    fun healthAt(snap: Snapshot, model: BridgeModel, at: Instant): Double? {
        val home = snap.hqSystem ?: return null
        val goods = goods(snap)
        if (goods.isEmpty()) return null
        val scores = snap.marketsIn(home).flatMap { m ->
            val history = model.prices(m.symbol).filter { it.observedAt <= at && it.good.symbol in goods }
            history.groupBy { it.good.symbol }.values.map { obs -> MarketHealth.score(obs.maxBy { it.observedAt }.good) }
        }
        return scores.takeIf { it.isNotEmpty() }?.average()
    }

    /** The chain's health now and [span] ago; the span is an hour, or as far back as the history goes past a quarter hour. */
    class Reading(val now: Double?, val hourAgo: Double?, val span: Duration = Duration.ofHours(1), val loading: Boolean = false) {
        val delta: Double? get() = if (now != null && hourAgo != null) now - hourAgo else null

        /** Rising, flat or falling over the hour; flat means a move under three points either way. */
        val arrow: Char get() = delta?.let { when { it > 0.03 -> '↗'; it < -0.03 -> '↘'; else -> '→' } } ?: ' '
        val tone: Rgb get() = when { now == null -> Palette.textDim; now >= 0.6 -> Palette.good; now >= 0.4 -> Palette.text; else -> Palette.warn }
    }

    fun reading(snap: Snapshot, model: BridgeModel): Reading {
        val now = model.now()
        val current = Summary.chainHealth(snap)
        healthAt(snap, model, now.minus(Duration.ofHours(1)))?.let { return Reading(current, it) }
        // Less than an hour of history: measure from the earliest reading, if it is old enough to mean anything.
        val home = snap.hqSystem ?: return Reading(current, null)
        val goods = goods(snap)
        val observations = snap.marketsIn(home).flatMap { m -> model.prices(m.symbol).filter { it.good.symbol in goods }.map { it.observedAt } }
        val earliest = observations.minOrNull() ?: return Reading(current, null, loading = true)
        val span = Duration.between(earliest, now)
        if (span < Duration.ofMinutes(15)) return Reading(current, null, span)
        return Reading(current, healthAt(snap, model, earliest), span)
    }

    /** The one line for the bridge: the health, the direction, and the move in points. */
    fun summaryLine(snap: Snapshot, model: BridgeModel): Line {
        if (snap.hqSystem == null) return Line("gate chain: no home system", Palette.textDim)
        val bill = snap.constructionBill ?: return Line("gate chain: bill not read yet", Palette.textDim)
        if (bill.none { it.fulfilled < it.required }) return Line("gate chain: every material delivered", Palette.good)
        val r = reading(snap, model)
        val now = r.now ?: return Line("gate chain: no producer read yet", Palette.textDim)
        val move = r.delta?.let { d -> " ${if (d >= 0) "+" else ""}${(d * 100).toInt()} in ${if (r.span >= Duration.ofMinutes(59)) "the hour" else Format.span(r.span)}" }
            ?: if (r.loading) " (history loading)" else " (under a quarter hour of history)"
        return Line("gate chain ${(now * 100).toInt()}% ${r.arrow}$move · ${producers(snap).size} producers", r.tone, bold = true)
    }

    /**
     * The economy screen's list: every producer of a chain good in the home system, worst first,
     * with supply and activity and the inputs it is starving for. Bill materials carry a diamond,
     * deeper inputs a dot; restricted producers are red, starved ones amber.
     */
    fun lines(snap: Snapshot, model: BridgeModel): List<Line> {
        if (snap.hqSystem == null) return emptyList()
        val bill = snap.constructionBill ?: return listOf(Line("bill not read yet", Palette.textDim))
        val roots = bill.filter { it.fulfilled < it.required }.map { it.tradeSymbol }
        if (roots.isEmpty()) return listOf(Line("every material delivered", Palette.good))
        val lines = mutableListOf(summaryLine(snap, model))
        lines += Line("for ${roots.joinToString(", ") { it.name }}", Palette.textDim)
        producers(snap).sortedBy { (_, _, g) -> MarketHealth.score(g) }.forEach { (m, good, listing) ->
            val starved = MarketHealth.starvedInputs(m, good)
            val mark = if (good in roots) '◆' else '·'
            val text = "$mark ${good.name.take(18).padEnd(18)} ${m.symbol.substringAfterLast('-').padEnd(4)} ${MarketHealth.describe(listing).lowercase()}" +
                (if (starved.isNotEmpty()) " ← ${starved.joinToString(", ") { it.symbol.name }}" else "")
            lines += Line(text, when {
                listing.activity == ActivityLevel.RESTRICTED -> Palette.bad
                starved.isNotEmpty() -> Palette.warn
                MarketHealth.score(listing) >= 0.6 -> Palette.good
                else -> Palette.text
            })
        }
        return lines
    }
}

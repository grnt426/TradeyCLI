package bridge.views

import behaviour.decisions.CreditsTrend
import behaviour.decisions.Summary
import bridge.BridgeModel
import bridge.Format
import bridge.GateChain
import bridge.canvas.Attr
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.fx.Starfield
import bridge.glyphs.Palette
import bridge.scene.Bar
import bridge.scene.Bars
import bridge.scene.CreditsChart
import bridge.scene.Table
import bridge.scene.TextBlock
import knowledge.Strategy
import java.time.Duration
import java.time.Instant

/**
 * The money screen: the bank over the whole reset, the race against the account's other agents,
 * where credits went and came from, the contracts seen, and the gate's bill with the cost to
 * finish it at today's prices.
 */
class EconomyView : WidgetView() {
    override val title = "Economy"
    private var model: BridgeModel? = null
    private val stars = Starfield(seed = 17, density = 0.006)

    private val bank = CreditsChart(history = { model!!.allCredits() }, now = { model!!.now() }, dots = { model!!.dots }, projection = false)
    private val race = Table(
        columns = listOf(
            Table.Column("agent", 12),
            Table.Column("phase", 6),
            Table.Column("ships", 5, alignRight = true),
            Table.Column("bank", 9, alignRight = true),
            Table.Column("cr/h", 7, alignRight = true),
            Table.Column("last h", 7, alignRight = true),
            Table.Column("gate"),
        ),
    )
    private val spent = Bars(bars = { flows(Summary.spending(model!!.snapshot()), Palette.warn) }, empty = "nothing spent yet")
    private val earned = Bars(bars = { flows(Summary.revenue(model!!.snapshot()), Palette.good) }, empty = "nothing earned yet")
    private val chain = TextBlock(lines = { GateChain.lines(model!!.snapshot(), model!!) }, empty = "no home system")
    private val contracts = Table(
        columns = listOf(
            Table.Column("contract", 10),
            Table.Column("type", 9),
            Table.Column("deliver"),
            Table.Column("done", 9, alignRight = true),
            Table.Column("pays", 8, alignRight = true),
            Table.Column("net", 8, alignRight = true),
            Table.Column("deadline", 9, alignRight = true),
            Table.Column("state", 9),
        ),
    )

    override fun paint(p: Painter, model: BridgeModel, t: Double) {
        this.model = model
        beginFrame()
        stars.paint(p, t)
        val snap = model.snapshot()
        val now = model.now()

        val (top, middle, bottom) = Rect(0, 0, p.width, p.height).rows(Len.fixed(12), Len.weight(), Len.fixed(if (p.height >= 34) 12 else 0))
        val (bankRect, raceRect) = top.cols(Len.weight(), Len.fixed((p.width * 0.4).toInt().coerceIn(56, 80)))
        place(bank, p.panel(bankRect, "Bank over the reset"), t)
        race.setRows(model.race().map { r ->
            Table.Row(
                r.agent,
                listOf(r.agent, r.phase, r.ships.toString(), Format.compact(r.bank), r.perHour?.let { Format.compact(it) } ?: "-", r.lastHour?.let { Format.compact(it) } ?: "-", r.gate),
                if (r.agent == snap.agent?.symbol) Palette.textBright else Palette.text,
            )
        })
        place(race, p.panel(raceRect, "Race · every agent of the account", focus === race), t)

        val (ledgerRect, contractsRect) = middle.cols(Len.weight(1), Len.weight(1))
        ledger(p.panel(ledgerRect, "Ledger · whole reset"), model, t)
        val records = model.contractRecords()
        contracts.setRows(records.sortedByDescending { it.seenAt }.map { rec ->
            val c = rec.contract
            val deliver = c.terms.deliver
            val done = deliver.sumOf { it.unitsFulfilled }
            val required = deliver.sumOf { it.unitsRequired }
            val deadline = runCatching { Instant.parse(c.terms.deadline) }.getOrNull()
            val state = when {
                c.fulfilled || rec.fulfilledAt != null -> "fulfilled"
                c.accepted -> "accepted"
                deadline != null && deadline.isBefore(now) -> "expired"
                else -> "offered"
            }
            Table.Row(
                c.id,
                listOf(
                    c.id.takeLast(8), c.type.lowercase(),
                    deliver.joinToString("; ") { "${it.unitsRequired} ${it.tradeSymbol.name} → ${it.destinationSymbol.substringAfterLast('-')}" },
                    "$done/$required", Format.compact(rec.payment),
                    (if (rec.profit >= 0) "+" else "") + Format.compact(rec.profit),
                    deadline?.let { if (it.isAfter(now)) Format.span(Duration.between(now, it)) else "past" } ?: "-", state,
                ),
                when (state) { "fulfilled" -> if (rec.profit >= 0) Palette.good else Palette.warn; "accepted" -> Palette.info; "expired" -> Palette.textDim; else -> Palette.text },
            )
        })
        val profit = records.filter { it.fulfilledAt != null }.sumOf { it.profit }
        place(contracts, p.panel(contractsRect, "Contracts (${records.size})", focus === contracts, hint = "fulfilled net ${if (profit >= 0) "+" else ""}${Format.compact(profit)}"), t)

        if (bottom.h > 0) {
            val (gateRect, chainRect) = bottom.cols(Len.weight(3), Len.weight(2))
            gate(p.panel(gateRect, "Gate"), model)
            place(chain, p.panel(chainRect, "Gate chain · the markets that feed it", hint = "worst first"), t)
        }
        endFrame(listOf(race, contracts))
    }

    private fun ledger(p: Painter, model: BridgeModel, t: Double) {
        val snap = model.snapshot()
        val spentTotal = Summary.spending(snap).sumOf { it.credits }
        val earnedTotal = Summary.revenue(snap).sumOf { it.credits }
        val net = earnedTotal - spentTotal
        var x = 0
        x += p.text(x, 0, "earned ${Format.credits(earnedTotal)}", Palette.good) + 3
        x += p.text(x, 0, "spent ${Format.credits(spentTotal)}", Palette.warn) + 3
        p.text(x, 0, "net ${if (net >= 0) "+" else ""}${Format.credits(net)}", if (net >= 0) Palette.good else Palette.bad, null, Attr.BOLD)
        val (l, r) = Rect(0, 2, p.width, p.height - 2).cols(Len.weight(), Len.weight())
        p.text(l.x, 1, "spent on", Palette.textDim)
        p.text(r.x, 1, "earned from", Palette.textDim)
        place(spent, p.sub(Rect(l.x, l.y, l.w - 1, l.h)), t)
        place(earned, p.sub(r), t)
    }

    private fun flows(flows: List<behaviour.decisions.Flow>, tone: bridge.canvas.Rgb): List<Bar> {
        val top = flows.maxOfOrNull { it.share } ?: return emptyList()
        return flows.map { f -> Bar(f.category, Format.compact(f.credits) + " ${(f.share * 100).toInt()}%", if (top > 0) f.share / top else 0.0, tone) }
    }

    private fun gate(p: Painter, model: BridgeModel) {
        val snap = model.snapshot()
        val now = model.now()
        val bill = snap.constructionBill
        val site = snap.hqSystem?.let { h -> snap.waypointsIn(h).firstOrNull { it.isUnderConstruction } }
        if (site == null) {
            p.text(0, 0, "no construction site in ${snap.hqSystem ?: "the home system"}; the gate is open or unknown", Palette.textDim)
            return
        }
        if (bill == null) {
            p.text(0, 0, "${site.symbol}: bill not read yet", Palette.textDim)
            return
        }
        var y = 0
        val remaining = Strategy.remainingCost(bill, snap)
        val credits = snap.agent?.credits ?: 0L
        var x = p.text(0, y, site.symbol, Palette.textBright, null, Attr.BOLD) + 2
        if (remaining != null) {
            val comfortable = Strategy.gateRush(credits, remaining)
            x += p.text(x, y, "to finish ~${Format.credits(remaining)} at today's prices", Palette.text) + 2
            p.text(x, y, if (comfortable) "rush on" else "need ${Format.credits(Strategy.comfortableBank(remaining))} to rush", if (comfortable) Palette.good else Palette.warn)
        }
        y++
        val markets = snap.hqSystem?.let { snap.marketsIn(it) } ?: emptyList()
        bill.forEach { m ->
            if (y >= p.height) return
            val done = m.fulfilled >= m.required
            val cheapest = markets.mapNotNull { it.good(m.tradeSymbol)?.purchasePrice }.minOrNull()
            p.text(0, y, m.tradeSymbol.name.take(18).padEnd(18), if (done) Palette.good else Palette.text)
            p.gauge(19, y, (p.width / 2 - 20).coerceAtLeast(6), if (m.required == 0L) 1.0 else m.fulfilled.toDouble() / m.required, if (done) Palette.good else Palette.accent)
            p.text(p.width / 2 + 1, y, "${m.fulfilled}/${m.required}", Palette.textDim)
            if (!done && cheapest != null) p.textRight(p.width, y, "cheapest $cheapest · ${Format.compact((m.required - m.fulfilled) * cheapest)} to finish", Palette.textDim)
            y++
        }
        val trend = CreditsTrend.trend(snap.creditsHistory, now)
        Summary.progress(snap, now, trend).lines.forEach { line ->
            if (y >= p.height) return
            p.text(0, y++, line.text.take(p.width), when (line.tone) { behaviour.decisions.Intent.Tone.GOOD -> Palette.good; behaviour.decisions.Intent.Tone.WARN -> Palette.warn; else -> Palette.text })
        }
    }
}

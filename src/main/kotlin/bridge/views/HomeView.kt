package bridge.views

import behaviour.decisions.CreditsTrend
import behaviour.decisions.Intent
import behaviour.decisions.Intentions
import behaviour.decisions.Summary
import bridge.BridgeModel
import bridge.Format
import bridge.canvas.Attr
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Rgb
import bridge.fx.Starfield
import bridge.glyphs.Palette
import bridge.scene.Bar
import bridge.scene.Bars
import bridge.scene.CreditsChart
import bridge.scene.Feed
import bridge.scene.Line
import bridge.scene.Table
import bridge.scene.TextBlock
import model.BootProgress
import model.ship.Ship
import model.ship.ShipNavStatus
import java.time.Duration

/**
 * Home: where the agent stands. The header, then three rows: the phase's progress, the bank's
 * chart and market health; the fleet with the selected ship's card and the event feed; the plan's
 * intentions and where credits went and came from. Short windows drop the bottom row, then the
 * top one, so the fleet always shows.
 */
class HomeView : WidgetView() {
    override val title = "Bridge"
    private val stars = Starfield(seed = 11, density = 0.006)
    private var model: BridgeModel? = null
    private var selectedShip: String? = null

    private fun snap() = model!!.snapshot()
    private fun now() = model!!.now()

    private val fleet = Table(
        columns = listOf(
            Table.Column("ship", 14),
            Table.Column("type", 15),
            Table.Column("behaviour", 12),
            Table.Column("phase"),
            Table.Column("where", 16),
            Table.Column("fuel", 7, alignRight = true),
            Table.Column("hold", 7, alignRight = true),
        ),
        onSelect = { row -> selectedShip = row?.key },
    )
    private val health = Table(
        columns = listOf(
            Table.Column("system", 9),
            Table.Column("health", 6, alignRight = true),
            Table.Column("mkts", 4, alignRight = true),
            Table.Column("restr", 5, alignRight = true),
            Table.Column("scarce", 6, alignRight = true),
            Table.Column("buried", 6, alignRight = true),
            Table.Column("oldest", 6, alignRight = true),
        ),
    )
    private val feed = Feed(lines = { model?.feed() ?: emptyList() }, now = { now() })
    private val progress = TextBlock(lines = { progressLines() })
    private val credits = CreditsChart(history = { snap().creditsHistory }, now = { now() }, dots = { model!!.dots })
    private val plan = TextBlock(lines = { planLines() }, empty = "no plan")
    private val spent = Bars(bars = { flows(Summary.spending(snap()), Palette.warn) }, empty = "nothing spent yet")
    private val earned = Bars(bars = { flows(Summary.revenue(snap()), Palette.good) }, empty = "nothing earned yet")

    override fun paint(p: Painter, model: BridgeModel, t: Double) {
        this.model = model
        beginFrame()
        stars.paint(p, t)
        val snap = model.snapshot()
        val now = model.now()
        header(p, model)

        val body = Rect(0, 1, p.width, p.height - 1)
        val topH = if (body.h >= 26) 11 else 0
        val bottomH = if (body.h >= 36) 9 else 0
        val (top, middle, bottom) = body.rows(Len.fixed(topH), Len.weight(), Len.fixed(bottomH))

        if (topH > 0) {
            val healthW = if (p.width >= 150) 54 else 0
            val (progressRect, creditsRect, healthRect) =
                if (healthW > 0) top.cols(Len.weight(3), Len.weight(4), Len.fixed(healthW)) else top.cols(Len.weight(3), Len.weight(4), Len.weight(3))
            val phase = snap.plan?.phase?.name ?: "NO PLAN"
            place(progress, p.panel(progressRect, "Phase · $phase"), t)
            place(credits, p.panel(creditsRect, "Credits", hint = "last hours, then the trend"), t)
            healthRows(snap, now)
            place(health, p.panel(healthRect, "Market health", focus === health), t)
        }

        val sideWidth = (p.width / 3).coerceIn(38, 56)
        val (left, right) = middle.cols(Len.weight(), Len.fixed(sideWidth))
        val (cardRect, feedRect) = right.rows(Len.fixed(15), Len.weight())
        fleetRows(snap, now)
        place(fleet, p.panel(left, "Fleet (${snap.ships.size})", focus === fleet, hint = "↑↓ · click"), t)
        shipCard(p.panel(cardRect, selectedShip ?: "Ship"), snap.ships[selectedShip], model)
        place(feed, p.panel(feedRect, "Events", hint = "wheel"), t)

        if (bottomH > 0) {
            val (planRect, spentRect, earnedRect) = bottom.cols(Len.weight(3), Len.weight(2), Len.weight(2))
            place(plan, p.panel(planRect, "Plan"), t)
            place(spent, p.panel(spentRect, "Spent on"), t)
            place(earned, p.panel(earnedRect, "Earned from"), t)
        }
        endFrame(listOfNotNull(fleet, if (topH > 0) health else null))
    }

    private fun header(p: Painter, model: BridgeModel) {
        val snap = model.snapshot()
        val now = model.now()
        p.fill(Rect(0, 0, p.width, 1), ' ', Palette.text, Palette.panel)
        var x = 1
        x += p.text(x, 0, "TRADEY", Palette.accent, Palette.panel, Attr.BOLD) + 2
        val agent = snap.agent
        if (agent == null) {
            p.text(x, 0, BootProgress.failure ?: BootProgress.current ?: "booting", if (BootProgress.failure != null) Palette.bad else Palette.textDim, Palette.panel)
        } else {
            x += p.text(x, 0, agent.symbol, Palette.textBright, Palette.panel, Attr.BOLD) + 2
            x += p.text(x, 0, agent.startingFaction, Palette.textDim, Palette.panel) + 2
            x += p.text(x, 0, Format.credits(agent.credits) + " cr", Palette.good, Palette.panel) + 2
            val phase = snap.plan?.phase?.name ?: "NO PLAN"
            x += p.text(x, 0, phase, Palette.accent, Palette.panel, Attr.BOLD) + 2
            val runner = snap.runner
            val (driver, tone) = when {
                runner == null -> "nobody is running the plan" to Palette.warn
                runner.isLive(now, Intentions.processAlive) -> "run pid ${runner.pid}, heartbeat ${Format.age(runner.heartbeat, now)} ago" to Palette.good
                else -> "run pid ${runner.pid} is gone" to Palette.warn
            }
            p.text(x, 0, driver, tone, Palette.panel)
        }
        val reset = snap.nextReset?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
        val right = buildString {
            if (reset != null) append("reset in ${Format.span(Duration.between(now, reset))}  ")
            append("%2.0f fps %5d B".format(model.fps, model.lastFrameBytes))
        }
        p.textRight(p.width - 1, 0, right, Palette.textDim, Palette.panel)
    }

    private fun fleetRows(snap: engine.Snapshot, now: java.time.Instant) {
        // Summary names ships by their number; the table keys on the full symbol.
        val bySuffix = snap.ships.values.associateBy { it.symbol.substringAfterLast('-') }
        fleet.setRows(Summary.fleet(snap, now).map { r ->
            val ship = bySuffix[r.ship]
            Table.Row(
                key = ship?.symbol ?: r.ship,
                cells = listOf(
                    ship?.symbol ?: r.ship, r.type, r.behaviour, listOf(r.phase, r.detail).filter { it.isNotBlank() }.joinToString(" "),
                    r.where,
                    ship?.let { if (it.fuel.capacity > 0) "${it.fuel.current}/${it.fuel.capacity}" else "-" } ?: "",
                    ship?.let { "${it.cargo.units}/${it.cargo.capacity}" } ?: "",
                ),
                tone = tone(r.tone),
            )
        })
    }

    private fun healthRows(snap: engine.Snapshot, now: java.time.Instant) {
        health.setRows(Summary.marketHealth(snap, now).map { h ->
            Table.Row(
                key = h.system,
                cells = listOf(
                    h.system, "${(h.score * 100).toInt()}%", h.markets.toString(), h.restrictedExports.toString(),
                    h.scarce.toString(), h.saturatedImports.toString(),
                    h.oldestReadHours?.let { if (it >= 1) "%.0fh".format(it) else "<1h" } ?: "-",
                ),
                tone = when { h.score >= 0.6 -> Palette.good; h.score >= 0.4 -> Palette.text; else -> Palette.warn },
            )
        })
    }

    private fun progressLines(): List<Line> {
        val snap = snap()
        val now = now()
        val trend = CreditsTrend.trend(snap.creditsHistory, now)
        val progress = Summary.progress(snap, now, trend)
        val lines = mutableListOf(Line(progress.headline, Palette.textBright, bold = true))
        snap.constructionBill?.forEach { m ->
            val done = m.fulfilled >= m.required
            lines += Line("  ${m.tradeSymbol.name.padEnd(18)} ${gaugeText(m.fulfilled, m.required)} ${m.fulfilled}/${m.required}", if (done) Palette.good else Palette.text)
        }
        progress.lines.forEach { lines += Line(it.text, tone(it.tone)) }
        return lines
    }

    /** A ten-cell text gauge, for lines that are text rather than paint. */
    private fun gaugeText(done: Long, total: Long): String {
        val cells = 10
        val filled = if (total <= 0) cells else (done * cells / total).toInt().coerceIn(0, cells)
        return "▰".repeat(filled) + "▱".repeat(cells - filled)
    }

    private fun planLines(): List<Line> {
        val snap = snap()
        val plan = snap.plan ?: return emptyList()
        val trend = CreditsTrend.trend(snap.creditsHistory, now())
        val ships = plan.assignments.map { it.ship }.toSet()
        val lines = Intentions.describe(snap, now(), trend)
            .filter { intent -> ships.none { intent.text.startsWith(it) } && !intent.text.startsWith("Plan driven") && !intent.text.startsWith("Run process") && !intent.text.startsWith("Nobody") }
            .map { Line(it.text, tone(it.tone)) }
            .toMutableList()
        plan.chains.forEach { c -> lines += Line("chain ${c.id}: ${c.ships.size} ships, ${c.legs.size} legs" + (if (c.hold) ", held" else ""), Palette.textDim) }
        return lines
    }

    private fun flows(flows: List<behaviour.decisions.Flow>, tone: Rgb): List<Bar> {
        val top = flows.maxOfOrNull { it.share } ?: return emptyList()
        return flows.map { f -> Bar(f.category, Format.compact(f.credits) + " ${(f.share * 100).toInt()}%", if (top > 0) f.share / top else 0.0, tone) }
    }

    private fun shipCard(p: Painter, ship: Ship?, model: BridgeModel) {
        if (ship == null) {
            p.text(0, 0, "select a ship", Palette.textDim)
            return
        }
        val snap = model.snapshot()
        val now = model.now()
        var y = 0
        p.text(0, y, ship.registration.role.name, Palette.accent, null, Attr.BOLD)
        p.textRight(p.width, y++, ship.frame.name.removePrefix("Frame "), Palette.textDim)
        val status = snap.shipStatus[ship.symbol]
        if (status != null) {
            p.text(0, y++, "${status.behaviour}: ${status.phase}".take(p.width), Palette.text)
            if (status.detail.isNotBlank()) p.text(2, y++, status.detail.take(p.width - 2), Palette.textDim)
            p.text(2, y++, "for ${Format.age(status.since, now)}", Palette.textDim)
        } else {
            p.text(0, y++, "no behaviour", Palette.textDim)
        }
        y++
        val nav = ship.nav
        when {
            nav.inTransitAt(now) -> {
                val total = Duration.between(nav.route.departureTime, nav.route.arrival).seconds.coerceAtLeast(1)
                val done = Duration.between(nav.route.departureTime, now).seconds.coerceIn(0, total)
                p.text(0, y++, "${nav.route.origin.symbol} → ${nav.route.destination.symbol}".take(p.width), Palette.info)
                p.text(0, y, "eta ${Format.clock(Duration.between(now, nav.route.arrival))}", Palette.text)
                p.gauge(11, y++, (p.width - 11).coerceAtLeast(4), done.toDouble() / total, Palette.info)
            }
            nav.status == ShipNavStatus.DOCKED -> p.text(0, y++, "docked at ${nav.waypointSymbol}", Palette.text)
            else -> p.text(0, y++, "in orbit of ${nav.waypointSymbol}", Palette.text)
        }
        p.text(0, y++, "${nav.flightMode.name.lowercase()} · ${ship.engine.name.removePrefix("Engine ")}".take(p.width), Palette.textDim)
        y++
        if (ship.fuel.capacity > 0) {
            p.text(0, y, "fuel ", Palette.textDim)
            p.gauge(5, y, (p.width - 13).coerceAtLeast(4), ship.fuel.ratio, if (ship.fuel.ratio < 0.25) Palette.bad else Palette.warn)
            p.textRight(p.width, y++, "${ship.fuel.current}/${ship.fuel.capacity}", Palette.text)
        }
        p.text(0, y, "hold ", Palette.textDim)
        p.gauge(5, y, (p.width - 13).coerceAtLeast(4), ship.cargo.fillRatio, Palette.good)
        p.textRight(p.width, y++, "${ship.cargo.units}/${ship.cargo.capacity}", Palette.text)
        ship.cargo.inventory.sortedByDescending { it.units }.take((p.height - y).coerceAtLeast(0)).forEach { item ->
            p.text(5, y, item.symbol.name.take(p.width - 10), Palette.text)
            p.textRight(p.width, y++, item.units.toString(), Palette.textDim)
        }
        if (ship.cooldown.remainingSeconds > 0) {
            p.textRight(p.width, 0, "cooldown ${ship.cooldown.remainingSeconds}s", Palette.warn)
        }
    }

    private fun tone(t: Intent.Tone): Rgb = when (t) {
        Intent.Tone.GOOD -> Palette.good
        Intent.Tone.WARN -> Palette.warn
        Intent.Tone.NEUTRAL -> Palette.text
    }
}

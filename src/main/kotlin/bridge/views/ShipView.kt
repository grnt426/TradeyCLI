package bridge.views

import behaviour.decisions.Idle
import bridge.BridgeModel
import bridge.Format
import bridge.canvas.Attr
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Rgb
import bridge.fx.ShipArt
import bridge.fx.Starfield
import bridge.glyphs.Atlas
import bridge.glyphs.Palette
import bridge.scene.Keys
import bridge.tty.Input
import model.ship.Ship
import model.ship.ShipNavStatus
import java.time.Duration
import java.time.Instant

/**
 * One ship up close: its silhouette with the hold filling as cargo does, its parts and their
 * condition, where it is going, what it carries, and what it has bought and sold lately. Left
 * and Right step through the fleet.
 */
class ShipView : View {
    override val title = "Ship"
    private val stars = Starfield(seed = 9, density = 0.01)

    private fun current(model: BridgeModel): Ship? {
        val snap = model.snapshot()
        model.selectedShip?.let { snap.ships[it] }?.let { return it }
        return snap.ships.values.minByOrNull { it.symbol }?.also { model.selectedShip = it.symbol }
    }

    override fun paint(p: Painter, model: BridgeModel, t: Double) {
        stars.paint(p, t)
        val snap = model.snapshot()
        val now = model.now()
        val ship = current(model)
        if (ship == null) {
            p.panel(Rect(0, 0, p.width, p.height), "Ship").text(0, 0, "no ships loaded yet", Palette.textDim)
            return
        }
        val artW = (p.width / 2).coerceIn(34, 60)
        val (left, right) = Rect(0, 0, p.width, p.height).cols(Len.fixed(artW), Len.weight())
        val (artRect, partsRect) = left.rows(Len.weight(), Len.fixed(12))
        val (factsRect, routeRect, timeRect, cargoRect, logRect) = right.rows(Len.fixed(8), Len.fixed(6), Len.fixed(10), Len.weight(2), Len.weight(3))

        val role = Atlas.role(ship.registration.role)
        val inFlight = ship.nav.inTransitAt(now)
        val art = p.panel(artRect, "${ship.symbol} · ${ship.registration.role.name.lowercase()}", hint = "← → step through the fleet")
        ShipArt.draw(art, ship, t, inFlight)
        val status = snap.shipStatus[ship.symbol]
        if (status != null) {
            art.text(0, art.height - 1, "${status.behaviour}: ${status.phase} ${status.detail}".trim().take(art.width - 8), Palette.text)
            art.textRight(art.width, art.height - 1, Format.age(status.since, now), Palette.textDim)
        } else {
            art.text(0, art.height - 1, snap.plan?.assignmentFor(ship.symbol)?.let { "assigned ${it.behaviour}, not running" } ?: "no behaviour", Palette.textDim)
        }

        parts(p.panel(partsRect, "Parts"), ship)
        facts(p.panel(factsRect, ship.frame.name.removePrefix("Frame ")), ship, now)
        route(p.panel(routeRect, "Route"), ship, now)
        time(p.panel(timeRect, "Time · last day", hint = "busy ■ idle ■"), ship, model)
        cargo(p.panel(cargoRect, "Hold ${ship.cargo.units}/${ship.cargo.capacity}"), ship)
        log(p.panel(logRect, "Log · phases and trades"), ship, model)
    }

    /**
     * The last day as a strip, one cell per slice, green where the ship worked and amber where it
     * waited, then the numbers and what it was waiting on most.
     */
    private fun time(p: Painter, ship: Ship, model: BridgeModel) {
        val now = model.now()
        val records = model.snapshot().phases.filter { it.ship == ship.symbol }.sortedBy { it.at }
        if (records.isEmpty()) {
            p.text(0, 0, "no phase history yet", Palette.textDim)
            return
        }
        val start = now.minus(Duration.ofHours(24))
        val slices = p.width.coerceAtLeast(1)
        val sliceMillis = Duration.ofHours(24).toMillis() / slices
        for (i in 0 until slices) {
            val from = start.plusMillis(i * sliceMillis)
            val to = from.plusMillis(sliceMillis)
            // The phase in force through the slice is the last change before its end.
            val inForce = records.lastOrNull { !it.at.isAfter(to) }
            val colour = when {
                inForce == null || records.first().at.isAfter(to) -> Palette.track
                inForce.phase in Idle.IDLE_PHASES -> Palette.warn
                else -> Palette.good
            }
            p.put(i, 0, '▀', colour, Palette.track)
        }
        p.text(0, 1, "-24h", Palette.textDim)
        p.textRight(p.width, 1, "now", Palette.textDim)
        val idle = Idle.perShip(records, now).firstOrNull() ?: return
        var y = 2
        fun span(d: Duration) = if (d.isZero) "0m" else Format.span(d)
        p.text(0, y++, "busy ${span(idle.busy)} · idle ${span(idle.idle)} · ${(idle.share * 100).toInt()}% idle", if (idle.share > 0.5) Palette.warn else Palette.text)
        // The day by kind of activity: a strip proportional to the hours, then the hours themselves.
        val time = Idle.time(model.snapshot().activities.filter { it.ship == ship.symbol }, records, now).firstOrNull()
        if (time != null && time.byKind.isNotEmpty()) {
            val total = Idle.KINDS.sumOf { time.hours(it) }
            if (total > 0.01) {
                var x = 0
                val parts = Idle.KINDS.filter { time.hours(it) > 0 }
                parts.forEachIndexed { i, kind ->
                    val cells = if (i == parts.lastIndex) p.width - x else (time.hours(kind) / total * p.width).toInt()
                    for (dx in 0 until cells) p.put(x + dx, y, '▀', kindColour(kind), Palette.track)
                    x += cells
                }
                y++
                p.text(0, y++, parts.joinToString(" · ") { "$it %.1fh".format(time.hours(it)) }.take(p.width), Palette.text)
                val moving = time.hours("cruise") + time.hours("burn") + time.hours("drift")
                if (moving > 0.05) {
                    val drift = time.hours("drift") / moving
                    val drifts = Idle.drifts(model.snapshot().activities.filter { it.ship == ship.symbol }).firstOrNull()
                    p.text(0, y++, "drifted ${(drift * 100).toInt()}% of ${"%.1f".format(moving)} h under way" + (drifts?.let { d -> ", ${d.legs} leg${if (d.legs == 1) "" else "s"}" + (d.longest?.let { l -> ", longest ${Format.span(Duration.ofSeconds(l.seconds))} on ${l.behaviour}" } ?: "") } ?: ""), if (drift > 0.3) Palette.warn else Palette.textDim)
                }
            }
        }
        idle.reasons.entries.take((p.height - y).coerceAtLeast(0)).forEach { (reason, span) ->
            p.text(0, y, Format.span(span).padStart(5), Palette.textDim)
            p.text(6, y++, reason.take(p.width - 6), Palette.text)
        }
    }

    private fun kindColour(kind: String) = when (kind) {
        "cruise" -> Palette.info
        "burn" -> Palette.accent
        "drift" -> Palette.warn
        "extract", "siphon" -> Rgb(196, 140, 80)
        "survey" -> Rgb(200, 128, 240)
        "jump" -> Palette.textBright
        else -> Palette.text
    }

    private fun facts(p: Painter, ship: Ship, now: Instant) {
        var y = 0
        p.text(0, y, ship.registration.name, Palette.textBright, null, Attr.BOLD)
        p.textRight(p.width, y++, ship.registration.factionSymbol, Palette.textDim)
        p.text(0, y++, "reactor ${ship.reactor.name.removePrefix("Reactor ")} · ${ship.reactor.powerOutput} power".take(p.width), Palette.text)
        p.text(0, y++, "engine ${ship.engine.name.removePrefix("Engine ")} · speed ${ship.engine.speed} · ${ship.nav.flightMode.name.lowercase()}".take(p.width), Palette.text)
        p.text(0, y++, "crew ${ship.crew.current}/${ship.crew.capacity} (${ship.crew.required} required) · morale ${ship.crew.morale}".take(p.width), Palette.textDim)
        if (ship.fuel.capacity > 0) {
            p.text(0, y, "fuel ", Palette.textDim)
            p.gauge(5, y, (p.width - 15).coerceAtLeast(4), ship.fuel.ratio, if (ship.fuel.ratio < 0.25) Palette.bad else Palette.warn)
            p.textRight(p.width, y++, "${ship.fuel.current}/${ship.fuel.capacity}", Palette.text)
        } else p.text(0, y++, "no fuel tank: solar", Palette.textDim)
        if (ship.cooldown.remainingSeconds > 0) {
            p.text(0, y, "cool ", Palette.textDim)
            p.gauge(5, y, (p.width - 15).coerceAtLeast(4), 1 - ship.cooldown.remainingSeconds.toDouble() / ship.cooldown.totalSeconds.coerceAtLeast(1), Palette.info)
            p.textRight(p.width, y++, "${ship.cooldown.remainingSeconds}s", Palette.text)
        }
    }

    private fun parts(p: Painter, ship: Ship) {
        var y = 0
        fun condition(label: String, c: Float?) {
            p.text(0, y, label.padEnd(8), Palette.textDim)
            val v = (c ?: 1f).toDouble()
            p.gauge(8, y, (p.width - 8 - 6).coerceAtLeast(4), v, when { v < 0.4 -> Palette.bad; v < 0.7 -> Palette.warn; else -> Palette.good })
            p.textRight(p.width, y++, "${(v * 100).toInt()}%", Palette.textDim)
        }
        condition("frame", ship.frame.condition)
        condition("reactor", ship.reactor.condition)
        condition("engine", ship.engine.condition)
        y++
        p.text(0, y++, "mounts ${ship.mounts.size}/${ship.frame.mountingPoints}", Palette.textDim)
        for (m in ship.mounts) {
            if (y >= p.height) return
            p.put(1, y, '┬', Palette.warn)
            p.text(3, y++, (m.name + (if (m.strength > 0) " · strength ${m.strength}" else "")).take(p.width - 3), Palette.text)
        }
        p.text(0, y++, "modules ${ship.modules.size}/${ship.frame.moduleSlots}", Palette.textDim)
        for (m in ship.modules) {
            if (y >= p.height) return
            p.put(1, y, '▪', Palette.info)
            p.text(3, y++, (m.name + (if (m.capacity > 0) " · ${m.capacity}" else "")).take(p.width - 3), Palette.text)
        }
    }

    private fun route(p: Painter, ship: Ship, now: Instant) {
        val nav = ship.nav
        val r = nav.route
        when {
            nav.inTransitAt(now) -> {
                val total = Duration.between(r.departureTime, r.arrival).seconds.coerceAtLeast(1)
                val done = Duration.between(r.departureTime, now).seconds.coerceIn(0, total)
                val f = done.toDouble() / total
                p.text(0, 0, r.origin.symbol, Palette.textDim)
                p.textRight(p.width, 0, r.destination.symbol, Palette.info)
                // A track with the ship on it.
                val trackW = p.width
                for (x in 0 until trackW) p.put(x, 1, if (x < (trackW * f).toInt()) '━' else '╌', if (x < (trackW * f).toInt()) Palette.info else Palette.track)
                p.put((trackW * f).toInt().coerceIn(0, trackW - 1), 1, '◆', Palette.textBright, null, Attr.BOLD)
                p.text(0, 2, "departed ${Format.age(r.departureTime, now)} ago", Palette.textDim)
                p.textRight(p.width, 2, "arrives in ${Format.clock(Duration.between(now, r.arrival))}", Palette.text)
                val dist = kotlin.math.hypot((r.destination.x - r.origin.x).toDouble(), (r.destination.y - r.origin.y).toDouble())
                p.text(0, 3, "%.0f units · %s · %s".format(dist, nav.flightMode.name.lowercase(), Format.span(Duration.ofSeconds(total))), Palette.textDim)
            }
            nav.status == ShipNavStatus.DOCKED -> {
                p.text(0, 0, "docked at ${nav.waypointSymbol}", Palette.text)
                p.text(0, 1, "last leg ${r.origin.symbol} → ${r.destination.symbol}, arrived ${Format.age(r.arrival, now)} ago".take(p.width), Palette.textDim)
            }
            else -> {
                p.text(0, 0, "in orbit of ${nav.waypointSymbol}", Palette.text)
                p.text(0, 1, "last leg ${r.origin.symbol} → ${r.destination.symbol}, arrived ${Format.age(r.arrival, now)} ago".take(p.width), Palette.textDim)
            }
        }
    }

    private fun cargo(p: Painter, ship: Ship) {
        if (ship.cargo.inventory.isEmpty()) {
            p.text(0, 0, if (ship.cargo.capacity == 0) "no hold" else "empty", Palette.textDim)
            return
        }
        ship.cargo.inventory.sortedByDescending { it.units }.take(p.height).forEachIndexed { i, item ->
            p.text(0, i, item.symbol.name.take(20).padEnd(20), Palette.text)
            p.gauge(21, i, (p.width - 21 - 6).coerceAtLeast(4), item.units.toDouble() / ship.cargo.capacity.coerceAtLeast(1), Palette.good)
            p.textRight(p.width, i, item.units.toString(), Palette.textDim)
        }
    }

    /** One line of the ship's day: a phase change or a trade. */
    private class Entry(val at: Instant, val text: String, val tone: bridge.canvas.Rgb, val amount: String = "")

    /** Phase changes and trades in one list, newest first, so the trades sit inside the phases that made them. */
    private fun log(p: Painter, ship: Ship, model: BridgeModel) {
        val now = model.now()
        val snap = model.snapshot()
        val entries = ArrayList<Entry>()
        snap.recentTransactions.filter { it.shipSymbol == ship.symbol }.forEach { tx ->
            val at = runCatching { Instant.parse(tx.timestamp) }.getOrNull() ?: return@forEach
            val sale = tx.type.name == "SELL"
            entries += Entry(at, "${if (sale) "sold" else "bought"} ${tx.units} ${tx.tradeSymbol.name} at ${tx.waypointSymbol.substringAfterLast('-')}", if (sale) Palette.good else Palette.warn, (if (sale) "+" else "-") + Format.credits(tx.totalPrice.toLong()))
        }
        snap.phases.filter { it.ship == ship.symbol }.forEach { r ->
            entries += Entry(r.at, "${r.behaviour}: ${r.phase} ${r.detail}".trim(), if (r.phase in Idle.IDLE_PHASES) Palette.textDim else Palette.text)
        }
        if (entries.isEmpty()) {
            p.text(0, 0, "nothing recorded yet", Palette.textDim)
            return
        }
        entries.sortedByDescending { it.at }.take(p.height).forEachIndexed { i, e ->
            p.text(0, i, Format.age(e.at, now).padStart(4), Palette.textDim)
            p.text(5, i, e.text.take(p.width - 5 - e.amount.length - 1), e.tone)
            if (e.amount.isNotEmpty()) p.textRight(p.width, i, e.amount, e.tone)
        }
    }

    override fun onInput(input: Input, model: BridgeModel): Boolean {
        if (input !is Input.Key) return false
        val key = Keys.normalise(input.key)
        if (key != "ArrowLeft" && key != "ArrowRight") return false
        val all = model.snapshot().ships.keys.sorted()
        if (all.isEmpty()) return true
        val i = all.indexOf(model.selectedShip)
        model.selectedShip = all[((i + if (key == "ArrowRight") 1 else -1) % all.size + all.size) % all.size]
        return true
    }
}

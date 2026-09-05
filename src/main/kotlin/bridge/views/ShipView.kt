package bridge.views

import bridge.BridgeModel
import bridge.Format
import bridge.canvas.Attr
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
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
        val (factsRect, routeRect, cargoRect, logRect) = right.rows(Len.fixed(8), Len.fixed(6), Len.weight(1), Len.weight(1))

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
        cargo(p.panel(cargoRect, "Hold ${ship.cargo.units}/${ship.cargo.capacity}"), ship)
        log(p.panel(logRect, "Trades"), ship, model)
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

    private fun log(p: Painter, ship: Ship, model: BridgeModel) {
        val now = model.now()
        val ours = model.snapshot().recentTransactions.filter { it.shipSymbol == ship.symbol }.sortedByDescending { it.timestamp }
        if (ours.isEmpty()) {
            p.text(0, 0, "no trades in the last hours", Palette.textDim)
            return
        }
        ours.take(p.height).forEachIndexed { i, tx ->
            val at = runCatching { Instant.parse(tx.timestamp) }.getOrNull()
            val sale = tx.type.name == "SELL"
            p.text(0, i, (at?.let { Format.age(it, now) } ?: "").padStart(4), Palette.textDim)
            p.text(5, i, "${if (sale) "sold" else "bought"} ${tx.units} ${tx.tradeSymbol.name} at ${tx.waypointSymbol.substringAfterLast('-')}".take(p.width - 16), if (sale) Palette.good else Palette.warn)
            p.textRight(p.width, i, (if (sale) "+" else "-") + Format.credits(tx.totalPrice.toLong()), if (sale) Palette.good else Palette.warn)
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

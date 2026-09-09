package behaviour

import behaviour.decisions.Intentions
import behaviour.decisions.Tour
import engine.VerbFailure
import knowledge.Strategy
import model.market.TradeSymbol
import model.system.WaypointType
import plan.Stage
import plan.SystemRecord
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The explorer (docs/boom.md): 3,930 of the galaxy's 7,026 systems have no jump gate, so nobody
 * trading through the network has ever charted or traded them. A ship with a warp drive picks the
 * nearest gate-less system it can reach on its tank, warps there, charts every waypoint, reads
 * every market, and goes on. Each system it opens is recorded in the plan like a pioneered one.
 *
 * Two rules from the first day (2026-09-08). A warp is only taken when the tank brings the ship
 * back, unless the far side is known to sell fuel: A0 warped into X1-XT25 and sat with 51 fuel two
 * hops from that system's only market. And when nothing lies in reach, the ship goes through the
 * gates to the held system with the most gate-less neighbours instead of checking every half hour
 * from where it happens to stand: A2 waited nine hours at X1-ZX11.
 */
val warpChartSpec = BehaviourSpec(
    name = "warpChart",
    description = "Warp to the nearest unheld system without a jump gate, chart it and read its markets; repeat.",
    params = listOf(ParamSpec("reach", "Furthest warp to take, in distance units (default: half the tank, the whole tank where fuel is known to be sold)")),
    validate = { _, ship, _ -> buildList { if (!ship.canWarp) add("${ship.symbol} has no warp drive") } },
    run = { warpChart() },
)

suspend fun BehaviourScope.warpChart() {
    while (true) {
        clock.sleep(10.seconds)
        // Full tank first: the warp costs one fuel per unit of distance and there may be no market on the far side.
        if (me.fuel.current < me.fuel.capacity) phase("refuel") { fillTank() }
        val snap = snapshot()
        val from = snap.systems[me.nav.systemSymbol] ?: run { status("waiting", "${me.nav.systemSymbol} is not in the map; checking again in 10 minutes"); clock.sleep(10.minutes); continue }
        val held = shared.plan.systems.keys
        val fixed = param("reach")?.toDoubleOrNull()
        val fuel = me.fuel.current.toDouble()
        fun reach(system: model.system.System) = fixed ?: Strategy.warpReach(fuel, snap.marketsIn(system.symbol).any { it.trades(TradeSymbol.FUEL) })
        val target = Strategy.warpTargets(snap, held, from, ::reach).firstOrNull { (s, _) -> !shared.claimedByOther(s.symbol, ship) }
        if (target == null) {
            // Nothing within a safe warp of here: go where there is something, through the gates.
            val base = Strategy.warpBase(shared.plan, snap, me.fuel.capacity.toDouble())
            val hasGate = snap.waypointsIn(from.symbol).any { it.type == WaypointType.JUMP_GATE }
            if (base != null && base != from.symbol && hasGate) {
                val moved = phase("relocate", "to $base, which has gate-less neighbours in reach") { goToSystem(base) }
                if (moved) continue
                status(detail = "$base could not be reached from ${from.symbol}")
            }
            if (!hasGate) {
                // Stranded in a gate-less system: warp back to the nearest held system with a gate, then relocate through it.
                val home = Strategy.warpHome(shared.plan, snap, from, ::reach)
                if (home != null) {
                    val (system, distance) = home
                    val gate = snap.waypointsIn(system.symbol).firstOrNull { it.type == WaypointType.JUMP_GATE }?.symbol
                        ?: system.waypoints.first { it.type == WaypointType.JUMP_GATE }.symbol
                    val back = phase("warp home", "to ${system.symbol} (${distance.toInt()} away), the nearest gate") {
                        try { warpTo(ship, gate); true } catch (e: VerbFailure) { status(detail = "could not warp to ${system.symbol}: ${e.message}"); false }
                    }
                    if (back) continue
                }
            }
            status("waiting", "no unheld gate-less system within a safe warp of ${from.symbol}; checking again in 30 minutes")
            clock.sleep(30.minutes)
            continue
        }
        val (system, distance) = target
        shared.claim(ship, system.symbol)
        val destination = system.waypoints.firstOrNull { it.type == WaypointType.PLANET } ?: system.waypoints.first()
        val arrived = phase("warp", "to ${system.symbol} (${distance.toInt()} away)") {
            try { warpTo(ship, destination.symbol); true } catch (e: VerbFailure) { status(detail = "could not warp to ${system.symbol}: ${e.message}"); false }
        }
        if (!arrived) { shared.release(ship); clock.sleep(5.minutes); continue }
        shared.editPlan("warped into ${system.symbol}") { p ->
            p.withSystem(SystemRecord(system.symbol, Stage.SETTLE, gate = null, gateBuilt = false, pioneer = ship, arrivedAt = clock.now().toString(), note = "no gate; reached by warp"))
        }
        if (snapshot().waypointsIn(system.symbol).isEmpty()) phase("map", system.symbol) { loadSystem(system.symbol) }
        phase("chart", system.symbol) { chartSystem() }
        phase("read markets", system.symbol) { probeMarkets() }
        shared.release(ship)
        status(detail = "${system.symbol} charted and read; ${Intentions.format(agent().credits)} in the bank")
    }
}

/** Refuels here when here sells fuel, else at the nearest market in the system that does (or might: an unread market is tried). */
private suspend fun BehaviourScope.fillTank() {
    val snap = snapshot()
    val station = if (here.hasMarket) here else Tour.nearest(here, snap.waypointsIn(me.nav.systemSymbol).filter { it.hasMarket && snap.markets[it.symbol]?.trades(TradeSymbol.FUEL) != false })
    if (station == null) { status(detail = "no market in ${me.nav.systemSymbol} to refuel at"); return }
    try {
        if (station.symbol != here.symbol) travelTo(station.symbol)
        dock(ship)
        refuel(ship)
    } catch (e: VerbFailure) {
        status(detail = "could not refuel at ${station.symbol}: ${e.message}")
    }
}

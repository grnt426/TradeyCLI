package behaviour

import behaviour.decisions.Intentions
import engine.Travel
import engine.VerbFailure
import model.system.WaypointType
import plan.Stage
import plan.SystemRecord
import kotlin.time.Duration.Companion.minutes

/**
 * The explorer (docs/boom.md): 3,930 of the galaxy's 7,026 systems have no jump gate, so nobody
 * trading through the network has ever charted or traded them. A ship with a warp drive picks the
 * nearest gate-less system it can reach on its tank, warps there, charts every waypoint, reads
 * every market, and goes on. Each system it opens is recorded in the plan like a pioneered one.
 */
val warpChartSpec = BehaviourSpec(
    name = "warpChart",
    description = "Warp to the nearest unheld system without a jump gate, chart it and read its markets; repeat.",
    params = listOf(ParamSpec("reach", "Furthest warp to take, in distance units (default the tank)")),
    validate = { _, ship, _ -> buildList { if (!ship.canWarp) add("${ship.symbol} has no warp drive") } },
    run = { warpChart() },
)

suspend fun BehaviourScope.warpChart() {
    while (true) {
        clock.sleep(1.minutes.div(6))
        // Full tank first: the warp costs one fuel per unit of distance and there may be no market on the far side.
        if (here.hasMarket && me.fuel.current < me.fuel.capacity) runCatching { refuel(ship) }
        val snap = snapshot()
        val from = snap.systems[me.nav.systemSymbol] ?: run { status("waiting", "${me.nav.systemSymbol} is not in the map; checking again in 10 minutes"); clock.sleep(10.minutes); continue }
        val reach = param("reach")?.toDoubleOrNull() ?: (me.fuel.current.toDouble() - 5)
        val held = shared.plan.systems.keys
        val target = snap.systems.values
            .filter { s -> s.symbol != from.symbol && s.symbol !in held && s.waypoints.none { it.type == WaypointType.JUMP_GATE } && s.waypoints.isNotEmpty() && !shared.claimedByOther(s.symbol, ship) }
            .map { s -> s to Travel.distance(from.x.toInt(), from.y.toInt(), s.x.toInt(), s.y.toInt()) }
            .filter { (_, d) -> d <= reach }
            .minByOrNull { (_, d) -> d }
        if (target == null) {
            status("waiting", "no unheld gate-less system within ${reach.toInt()} of ${from.symbol}; checking again in 30 minutes")
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

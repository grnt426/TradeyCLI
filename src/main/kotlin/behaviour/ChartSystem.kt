package behaviour

import behaviour.decisions.Intentions
import behaviour.decisions.Tour
import engine.VerbFailure
import model.WaypointTraitSymbol

/**
 * Chart every uncharted waypoint in the ship's system, nearest first, and stop when none is left.
 * Charting pays a one-time reward per waypoint scaled by the rarity of its traits, so a probe on
 * the far side of a gate turns an empty map into credits and prices we can then read.
 */
val chartSystemSpec = BehaviourSpec(
    name = "chartSystem",
    description = "Visit and chart every uncharted waypoint in the ship's current system, nearest first, then stop.",
    params = listOf(ParamSpec("system", "System to chart, jumping there through the gate if needed; default: the ship's current one")),
    validate = { _, _, _ -> emptyList() },
    run = { chartSystem() },
)

suspend fun BehaviourScope.chartSystem() {
    val system = param("system")?.uppercase() ?: me.nav.systemSymbol
    if (me.nav.systemSymbol != system) {
        // Never more than CHART_HOPS jumps for charts, whatever sent the probe: a jump is antimatter at the local gate's
        // price, which our own jumps push up (thirty-jump kit trips on 2026-09-09 cost more than the charts paid).
        val gate = localGate(me.nav.systemSymbol)
        val hops = gate?.let { g -> knowledge.GateGraph.route(shared.gates, g.symbol, system, shared.unreachableGates)?.size }
        if (hops != null && hops > knowledge.Strategy.CHART_HOPS) {
            status("done", "$system is $hops jumps away, more than ${knowledge.Strategy.CHART_HOPS}; staying to watch prices here")
            return
        }
        phase("migrate", "to $system") {
            if (!goToSystem(system)) throw BehaviourFailure("$system cannot be reached: its gate is under construction")
        }
    }
    var charted = 0
    var earned = 0L
    while (true) {
        val snap = snapshot()
        val candidates = snap.waypointsIn(system).filter { it.hasTrait(WaypointTraitSymbol.UNCHARTED) && !shared.claimedByOther(it.symbol, ship) && it.symbol !in shared.chartedElsewhere }
        val next = Tour.nearest(here, candidates) ?: break
        shared.claim(ship, next.symbol)
        phase("travel", "to ${next.symbol} (${distanceTo(next.symbol).toInt()} away)") { travelTo(next.symbol) }
        phase("chart", next.symbol) {
            try {
                val reward = chart(ship)
                charted++
                earned += reward
                status(detail = "charted ${next.symbol} for ${Intentions.format(reward)}; $charted so far, ${Intentions.format(earned)} earned")
                // The chart response carries the waypoint with its traits; no second read needed.
                val now = snapshot().waypoints[next.symbol] ?: return@phase
                if (now.hasMarket) refreshMarket(next.symbol)
                if (now.hasShipyard) refreshShipyard(next.symbol)
            } catch (e: VerbFailure.Api) {
                status(detail = "${next.symbol} could not be charted: ${e.error.apiMessage}")
                // Someone else charted it first and our cached waypoint still says UNCHARTED: one read clears the
                // trait, or the probe retries the same 400 forever (5,800 an hour on 2026-09-07, most of the API budget).
                runCatching { verbs.waypoint(next.symbol) }
                shared.chartedElsewhere += next.symbol
            }
        }
    }
    shared.release(ship)
    status("done", "charted $charted waypoints in $system for ${Intentions.format(earned)}")
}

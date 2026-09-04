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
    params = listOf(ParamSpec("system", "System to chart; default: the ship's current one")),
    validate = { _, _, _ -> emptyList() },
    run = { chartSystem() },
)

suspend fun BehaviourScope.chartSystem() {
    val system = param("system") ?: me.nav.systemSymbol
    var charted = 0
    var earned = 0L
    while (true) {
        val snap = snapshot()
        val candidates = snap.waypointsIn(system).filter { it.hasTrait(WaypointTraitSymbol.UNCHARTED) && !shared.claimedByOther(it.symbol, ship) }
        val next = Tour.nearest(here, candidates) ?: break
        shared.claim(ship, next.symbol)
        phase("travel", "to ${next.symbol} (${distanceTo(next.symbol).toInt()} away)") { travelTo(next.symbol) }
        phase("chart", next.symbol) {
            try {
                val reward = chart(ship)
                charted++
                earned += reward
                status(detail = "charted ${next.symbol} for ${Intentions.format(reward)}; $charted so far, ${Intentions.format(earned)} earned")
                val now = verbs.waypoint(next.symbol)
                if (now.hasMarket) refreshMarket(next.symbol)
                if (now.hasShipyard) refreshShipyard(next.symbol)
            } catch (e: VerbFailure.Api) {
                status(detail = "${next.symbol} could not be charted: ${e.error.apiMessage}")
            }
        }
    }
    shared.release(ship)
    status("done", "charted $charted waypoints in $system for ${Intentions.format(earned)}")
}

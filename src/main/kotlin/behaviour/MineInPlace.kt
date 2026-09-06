package behaviour

import behaviour.decisions.Mining
import behaviour.decisions.Surveys
import engine.VerbFailure
import model.system.WaypointModifiers
import kotlin.time.Duration.Companion.minutes

/**
 * A drone that flies to its rock once and stays: it pulls while its hold has room and stops
 * when the hold is full, waiting for a collector hauler to take the ore. A mining drone's tank
 * cannot cruise both ways to any rock in a system like X1-ZJ35, and a full hold pulled again is
 * stock and stability thrown away, so the drone never travels after arrival and never over-pulls.
 * It leaves only when the rock reads CRITICAL_LIMIT or STRIPPED, to the next rock the plan names.
 */
val mineInPlaceSpec = BehaviourSpec(
    name = "mineInPlace",
    description = "Fly to a rock once and pull ore while the hold has room; a collector hauler takes it away.",
    params = listOf(
        ParamSpec("asteroid", "The rock to sit on; default: the best-paying reachable rock"),
        ParamSpec("surveys", "yes/no: use surveys made for this rock (default yes)"),
    ),
    validate = { snapshot, ship, params ->
        buildList {
            if (!ship.canMine) add("${ship.symbol} has no mining laser")
            params["asteroid"]?.let { a -> val w = snapshot.waypoints[a]; if (w == null) add("unknown waypoint $a") else if (!w.isMineable) add("$a is not an asteroid") }
        }
    },
    run = { mineInPlace() },
)

suspend fun BehaviourScope.mineInPlace() {
    val useSurveys = param("surveys")?.lowercase() != "no"
    val rock = param("asteroid")?.uppercase()
        ?: Mining.rank(snapshot(), me, clock.now()).firstOrNull { !it.asteroid.isSiphonable }?.asteroid?.symbol
        ?: throw BehaviourFailure("no rock to sit on")
    shared.claim(ship, rock)
    shared.parked[ship] = rock
    try {
        phase("travel", "to $rock (${distanceTo(rock).toInt()} away, once)") { travelTo(rock) }
        var pulled = 0
        while (true) {
            val here = verbs.waypoint(rock)
            if (here.hasModifier(WaypointModifiers.STRIPPED) || here.hasModifier(WaypointModifiers.CRITICAL_LIMIT)) {
                status("done", "$rock is ${here.modifiers.joinToString { it.symbol }}; pulled $pulled here; needs a new rock")
                return
            }
            if (me.cargoFull) {
                status("full", "${me.cargo.units}/${me.cargo.capacity} at $rock; waiting for a collector")
                clock.sleep(2.minutes)
                continue
            }
            phase("extract", "at $rock (${me.cargo.units}/${me.cargo.capacity})") {
                val plan = Mining.rank(snapshot(), me, clock.now()).firstOrNull { it.asteroid.symbol == rock }
                val survey = if (useSurveys && plan != null) Surveys.pick(surveysFor(rock), plan) else null
                try {
                    val got = extract(ship, survey)
                    pulled += got.units
                    status(detail = "+${got.units} ${got.good}; ${me.cargo.units}/${me.cargo.capacity}; $pulled pulled here")
                } catch (e: VerbFailure.SurveyUnusable) {
                    // stale survey: the next loop pulls without it
                } catch (e: VerbFailure.AsteroidDestabilized) {
                    status(detail = "$rock refused the pull (destabilised); resting 10 minutes")
                    clock.sleep(10.minutes)
                }
            }
        }
    } finally {
        shared.parked.remove(ship)
        shared.release(ship)
    }
}

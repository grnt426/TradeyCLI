package behaviour

import behaviour.decisions.Tour
import model.system.WaypointModifiers
import model.WaypointTraitSymbol
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * The surveyor's rounds: keep every mineable asteroid in the system covered by a survey with at
 * least [minLeft] minutes to run, nearest uncovered rock first, and re-read the system's waypoints
 * every [every] minutes so the stability modifiers (UNSTABLE, CRITICAL_LIMIT, STRIPPED) are current.
 * When everything is covered the ship rests where it is, so two surveyors cost a request every
 * couple of minutes, not a request budget. Miners use whatever survey is freshest for their rock.
 * Only a hull with a surveyor mount can do this: the surveyor drone or the command frigate; probes
 * carry no mounts.
 */
val surveySpec = BehaviourSpec(
    name = "survey",
    description = "Keep every asteroid in the system covered by a fresh survey and its stability re-read; rest when covered.",
    params = listOf(
        ParamSpec("system", "System to cover; default: the ship's"),
        ParamSpec("minLeft", "A survey with fewer minutes left than this is renewed (default 20)"),
        ParamSpec("every", "Minutes between re-reads of the system's waypoints for stability (default 30)"),
    ),
    validate = { _, ship, _ -> buildList { if (!ship.canSurvey) add("${ship.symbol} has no surveyor mount") } },
    run = { survey() },
)

suspend fun BehaviourScope.survey() {
    val system = param("system")?.uppercase() ?: me.nav.systemSymbol
    val minLeft = (param("minLeft")?.toLongOrNull() ?: 20).minutes
    val every = (param("every")?.toLongOrNull() ?: 30).minutes
    if (!me.canSurvey) throw BehaviourFailure("$ship has no surveyor mount")
    var lastReload: Instant? = null
    var surveyed = 0
    while (true) {
        val now = clock.now()
        if (lastReload == null || now.isAfter(lastReload.plusSeconds(every.inWholeSeconds))) {
            phase("read stability", system) { loadSystem(system) }
            lastReload = clock.now()
        }
        val snap = snapshot()
        val fresh = now.plusSeconds(minLeft.inWholeSeconds)
        val rocks = snap.asteroidsIn(system).filter { rock ->
            !rock.isSiphonable && !rock.hasTrait(WaypointTraitSymbol.STRIPPED) && !rock.hasModifier(WaypointModifiers.STRIPPED) &&
                !shared.claimedByOther(rock.symbol, ship) && snap.validSurveysFor(rock.symbol, now).none { it.expiration.isAfter(fresh) }
        }
        val next = Tour.nearest(here, rocks)
        if (next == null) {
            shared.release(ship)
            val covered = snap.asteroidsIn(system).count { !it.isSiphonable }
            status("resting", "$covered rocks covered; $surveyed surveys this shift; checking again in ${minOf(every, 10.minutes)}")
            clock.sleep(minOf(every, 10.minutes))
            continue
        }
        shared.claim(ship, next.symbol)
        // Rocks have no fuel: top up at the nearest market before the tank gets low, and route the leg through one when it cannot cover it.
        if (me.usesFuel && me.fuel.current < me.fuel.capacity / 4) {
            val station = Tour.nearest(here, snap.waypointsIn(system).filter { it.hasMarket })
            if (station != null) phase("refuel", "at ${station.symbol}") { travelVia(station.symbol); dock(ship); refuel(ship) }
        }
        phase("travel", "to ${next.symbol} (${distanceTo(next.symbol).toInt()} away)") { travelVia(next.symbol) }
        phase("survey", next.symbol) {
            val made = survey(ship)
            surveyed += made.size
            val best = made.maxByOrNull { it.deposits.size }
            status(detail = "${made.size} survey(s) at ${next.symbol}" + (best?.let { ": ${it.size.name.lowercase()}, ${it.goods.joinToString(",") { g -> g.name }}" } ?: ""))
        }
    }
}

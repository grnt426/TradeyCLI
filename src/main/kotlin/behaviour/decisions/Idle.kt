package behaviour.decisions

import storage.PhaseRecord
import java.time.Duration
import java.time.Instant

/** How one ship spent its time: busy, idle, and why it was idle. */
data class ShipIdle(val ship: String, val busy: Duration, val idle: Duration, val reasons: Map<String, Duration>) {
    val share: Double get() = (busy + idle).seconds.let { if (it == 0L) 0.0 else idle.seconds.toDouble() / it }
}

/**
 * Idle time from the phase log. "Ship has nothing to do" usually means the plan failed to find
 * something worth doing, so this is the opportunity cost the plan is leaving on the table: the
 * first thing to measure before deciding which behaviours saturate and which keep failing to
 * find work. A phase counts as idle when it is one of [IDLE_PHASES]; everything else is work,
 * including flying.
 */
object Idle {
    val IDLE_PHASES = setOf("waiting", "idle", "done", "failed", "skip")

    fun perShip(records: List<PhaseRecord>, now: Instant, since: Instant? = null): List<ShipIdle> =
        records.groupBy { it.ship }.map { (ship, list) ->
            val sorted = list.sortedBy { it.at }
            var busy = Duration.ZERO
            var idle = Duration.ZERO
            val reasons = mutableMapOf<String, Duration>()
            sorted.forEachIndexed { i, r ->
                val start = if (since != null && r.at.isBefore(since)) since else r.at
                val end = sorted.getOrNull(i + 1)?.at ?: now
                if (!end.isAfter(start)) return@forEachIndexed
                val span = Duration.between(start, end)
                if (r.phase in IDLE_PHASES) {
                    idle += span
                    val reason = "${r.behaviour}: ${r.phase} ${r.detail.substringBefore(';').take(60)}".trim()
                    reasons.merge(reason, span) { a, b -> a + b }
                } else busy += span
            }
            ShipIdle(ship, busy, idle, reasons.toList().sortedByDescending { it.second }.toMap())
        }.sortedByDescending { it.share }

    /** Idle share of the whole fleet's recorded time. */
    fun fleetShare(ships: List<ShipIdle>): Double {
        val total = ships.sumOf { (it.busy + it.idle).seconds }
        return if (total == 0L) 0.0 else ships.sumOf { it.idle.seconds }.toDouble() / total
    }

    val KINDS = listOf("cruise", "burn", "drift", "extract", "siphon", "survey", "jump")

    /** Where one ship's day went: hours by activity kind, plus idle from the phase log. Everything else is docking, trading and waiting on the API. */
    data class ShipTime(val ship: String, val byKind: Map<String, Duration>, val idle: Duration, val busy: Duration) {
        val recorded: Duration get() = busy + idle
        fun hours(kind: String): Double = (byKind[kind] ?: Duration.ZERO).toMinutes() / 60.0
    }

    fun time(activities: List<storage.ActivityRecord>, phases: List<storage.PhaseRecord>, now: Instant): List<ShipTime> =
        time(activities, perShip(phases, now))

    /** [time] with the idle figures already worked out, so a caller holding them does not pay for the phase log twice. */
    fun time(activities: List<storage.ActivityRecord>, perShip: List<ShipIdle>): List<ShipTime> {
        val idle = perShip.associateBy { it.ship }
        val byShip = activities.groupBy { it.ship }
        return (byShip.keys + idle.keys).map { ship ->
            val byKind = (byShip[ship] ?: emptyList()).groupBy { it.kind }.mapValues { (_, l) -> Duration.ofSeconds(l.sumOf { it.seconds }) }
            ShipTime(ship, byKind, idle[ship]?.idle ?: Duration.ZERO, idle[ship]?.busy ?: Duration.ZERO)
        }.sortedBy { it.ship }
    }

    /**
     * Every figure the day's phase and activity logs yield, worked out once. A day of a big
     * fleet's phases runs to hundreds of thousands of records, so a screen that redraws twenty
     * times a second must not walk them per frame: it keeps one of these per snapshot.
     */
    data class Report(val at: Instant, val perShip: List<ShipIdle>, val time: List<ShipTime>, val perBehaviour: Map<String, Pair<Duration, Duration>>) {
        val idleByShip: Map<String, ShipIdle> by lazy { perShip.associateBy { it.ship } }
        val timeByShip: Map<String, ShipTime> by lazy { time.associateBy { it.ship } }
        val fleetShare: Double get() = fleetShare(perShip)
    }

    fun report(activities: List<storage.ActivityRecord>, phases: List<PhaseRecord>, now: Instant): Report {
        val perShip = perShip(phases, now)
        return Report(now, perShip, time(activities, perShip), perBehaviour(phases, now))
    }

    /** Drift legs per ship with the job that sent them: every one is a route planned past the tank. */
    data class ShipDrift(val ship: String, val legs: Int, val time: Duration, val byBehaviour: Map<String, Int>, val longest: storage.ActivityRecord?)

    fun drifts(records: List<storage.ActivityRecord>): List<ShipDrift> =
        records.filter { it.kind == "drift" }.groupBy { it.ship }.map { (ship, list) ->
            ShipDrift(ship, list.size, Duration.ofSeconds(list.sumOf { it.seconds }), list.groupingBy { it.behaviour }.eachCount(), list.maxByOrNull { it.seconds })
        }.sortedByDescending { it.time }

    /** Idle time by behaviour: which plans keep failing to find work. */
    fun perBehaviour(records: List<PhaseRecord>, now: Instant): Map<String, Pair<Duration, Duration>> {
        val out = mutableMapOf<String, Pair<Duration, Duration>>()
        records.groupBy { it.ship }.forEach { (_, list) ->
            val sorted = list.sortedBy { it.at }
            sorted.forEachIndexed { i, r ->
                val end = sorted.getOrNull(i + 1)?.at ?: now
                if (!end.isAfter(r.at)) return@forEachIndexed
                val span = Duration.between(r.at, end)
                val (b, idle) = out[r.behaviour] ?: (Duration.ZERO to Duration.ZERO)
                out[r.behaviour] = if (r.phase in IDLE_PHASES) b to (idle + span) else (b + span) to idle
            }
        }
        return out
    }
}

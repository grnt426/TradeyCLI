package bridge

import model.system.OrbitalNames
import storage.ActivityRecord
import java.time.Duration
import java.time.Instant

/**
 * Jumps our ships made lately, read back from the activity log: a jump is instant, so the only
 * trace it leaves is the record, and the galaxy map animates the ones inside [WINDOW] along the
 * gate link they crossed.
 */
object RecentJumps {
    /** How far back the map looks. */
    val WINDOW: Duration = Duration.ofMinutes(5)

    /** One jump: the systems either side, the ship, and when. */
    data class Hop(val from: String, val to: String, val ship: String, val at: Instant) {
        /** 0 the moment it happened, 1 at the edge of the window. */
        fun age(now: Instant): Double = (Duration.between(at, now).toMillis().toDouble() / WINDOW.toMillis()).coerceIn(0.0, 1.0)
    }

    /**
     * The jumps inside the window, newest first. A record's detail is `gate -> gate`; records
     * older than that format named only the destination and are skipped, since a hop with no
     * origin has nothing to draw.
     */
    fun recent(activities: List<ActivityRecord>, now: Instant, window: Duration = WINDOW): List<Hop> {
        val since = now.minus(window)
        return activities.asSequence()
            .filter { it.kind == "jump" && !it.at.isBefore(since) && !it.at.isAfter(now) }
            .mapNotNull { r ->
                val arrow = r.detail.indexOf(" -> ")
                if (arrow < 0) return@mapNotNull null
                val from = r.detail.substring(0, arrow).trim()
                val to = r.detail.substring(arrow + 4).trim()
                if (from.count { it == '-' } < 2 || to.count { it == '-' } < 2) return@mapNotNull null
                Hop(OrbitalNames.getSectorSystem(from), OrbitalNames.getSectorSystem(to), r.ship, r.at)
            }
            .sortedByDescending { it.at }
            .toList()
    }

    /** Hops grouped by the link they crossed, in the direction flown, busiest first. */
    fun byLink(hops: List<Hop>): List<Pair<Pair<String, String>, List<Hop>>> =
        hops.groupBy { it.from to it.to }.entries.sortedByDescending { it.value.size }.map { it.key to it.value }
}

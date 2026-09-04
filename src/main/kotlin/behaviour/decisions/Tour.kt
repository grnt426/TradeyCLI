package behaviour.decisions

import engine.Travel
import model.system.Waypoint

object Tour {
    fun nearest(from: Waypoint, candidates: List<Waypoint>): Waypoint? =
        candidates.minByOrNull { Travel.distance(from.x, from.y, it.x, it.y) }

    /** Nearest-neighbour order from [start] through every waypoint in [stops]. Good enough for a probe run. */
    fun order(start: Waypoint, stops: List<Waypoint>): List<Waypoint> {
        val remaining = stops.toMutableList()
        val route = mutableListOf<Waypoint>()
        var at = start
        while (remaining.isNotEmpty()) {
            val next = nearest(at, remaining)!!
            remaining.remove(next)
            route += next
            at = next
        }
        return route
    }

    fun length(start: Waypoint, route: List<Waypoint>): Double {
        var at = start
        var total = 0.0
        route.forEach { total += Travel.distance(at.x, at.y, it.x, it.y); at = it }
        return total
    }
}

package engine

import model.ship.FlightMode
import model.system.Waypoint

/**
 * The cruise route from one waypoint to another through fuel stops, when the tank cannot cover
 * the leg. Drifting is ten times slower than cruising, so a ship should drift only when no chain
 * of stops exists at all. Dijkstra over the fuel markets: an edge is a cruise leg the full tank
 * can cover (the first leg only what is in the tank now, unless the ship can fill up where it is),
 * weighted by distance.
 */
object FuelRoute {

    /**
     * The stops to refuel at, in order, on the way from [from] to [to]; empty when the leg is
     * direct; null when no route exists. [stops] are the waypoints with fuel for sale.
     */
    fun plan(from: Waypoint, to: Waypoint, stops: List<Waypoint>, capacity: Long, fuelNow: Long, canFillHere: Boolean): List<Waypoint>? {
        val firstLegBudget = if (canFillHere) capacity else fuelNow
        fun cost(a: Waypoint, b: Waypoint): Long = Travel.fuelCost(Travel.distance(a.x, a.y, b.x, b.y), FlightMode.CRUISE)
        if (cost(from, to) <= firstLegBudget) return emptyList()
        val nodes = (stops.filter { it.symbol != from.symbol && it.symbol != to.symbol } + to).distinctBy { it.symbol }
        val dist = mutableMapOf(from.symbol to 0.0)
        val prev = mutableMapOf<String, Waypoint>()
        val bySymbol = (nodes + from).associateBy { it.symbol }
        val open = mutableSetOf(from.symbol)
        val done = mutableSetOf<String>()
        while (open.isNotEmpty()) {
            val current = open.minByOrNull { dist.getValue(it) }!!
            open -= current
            done += current
            if (current == to.symbol) break
            val here = bySymbol.getValue(current)
            val budget = if (current == from.symbol) firstLegBudget else capacity
            for (next in nodes) {
                if (next.symbol in done) continue
                if (cost(here, next) > budget) continue
                val d = dist.getValue(current) + Travel.distance(here.x, here.y, next.x, next.y)
                if (d < (dist[next.symbol] ?: Double.MAX_VALUE)) {
                    dist[next.symbol] = d
                    prev[next.symbol] = here
                    open += next.symbol
                }
            }
        }
        if (to.symbol !in dist) return null
        val path = mutableListOf<Waypoint>()
        var cursor: Waypoint? = prev[to.symbol]
        while (cursor != null && cursor.symbol != from.symbol) {
            path += cursor
            cursor = prev[cursor.symbol]
        }
        return path.reversed()
    }
}

package knowledge

/**
 * The jump-gate network as far as our ships have read it: gate waypoint -> the gates it connects
 * to. A jump goes one hop; a ship bound for a system two gates away takes the hops in turn. Home
 * can therefore buy for the whole connected network, at the cost of the hops (a jump has a
 * cooldown and a fee, not a fuel cost).
 */
object GateGraph {
    /** The gates to jump to, in order, from [from] to any gate in [toSystem]; null when no known path avoids [blocked]. */
    fun route(gates: Map<String, List<String>>, from: String, toSystem: String, blocked: Set<String> = emptySet()): List<String>? {
        if (from.substringBeforeLast('-') == toSystem) return emptyList()
        val previous = mutableMapOf<String, String>()
        val queue = ArrayDeque(listOf(from))
        val seen = mutableSetOf(from)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            for (next in gates[node].orEmpty()) {
                if (next in seen || next in blocked) continue
                seen += next
                previous[next] = node
                if (next.substringBeforeLast('-') == toSystem) {
                    val path = ArrayDeque<String>()
                    var at = next
                    while (at != from) { path.addFirst(at); at = previous.getValue(at) }
                    return path.toList()
                }
                queue.addLast(next)
            }
        }
        return null
    }

    /**
     * Every system within [maxHops] jumps of [from] over the gates we know, with the jumps it takes;
     * [from]'s own system at 0. A gate whose connections were never read ends the search there.
     */
    fun hopsFrom(gates: Map<String, List<String>>, from: String, maxHops: Int, blocked: Set<String> = emptySet()): Map<String, Int> {
        val out = mutableMapOf(from.substringBeforeLast('-') to 0)
        val seen = mutableSetOf(from)
        var frontier = listOf(from)
        for (hop in 1..maxHops) {
            val next = mutableListOf<String>()
            for (node in frontier) for (gate in gates[node].orEmpty()) {
                if (gate in seen || gate in blocked) continue
                seen += gate
                next += gate
                out.putIfAbsent(gate.substringBeforeLast('-'), hop)
            }
            frontier = next
        }
        return out
    }
}

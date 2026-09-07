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
}

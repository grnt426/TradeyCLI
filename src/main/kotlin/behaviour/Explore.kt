package behaviour

import behaviour.decisions.Intentions
import engine.VerbFailure
import model.system.WaypointType

/**
 * The scout: in the system it stands in, chart what is uncharted, read every market and
 * shipyard, then go to the gate and jump to a system nobody of ours has seen, and start again.
 * Stops where there is no finished gate or nothing new beyond it. A probe flies for free, so
 * each system costs one unit of antimatter and the requests to read it.
 */
val exploreSpec = BehaviourSpec(
    name = "explore",
    description = "Chart and read the current system, then jump through its gate to an unvisited system; repeat until the map runs out.",
    params = listOf(ParamSpec("maxSystems", "Stop after this many systems (default 10)")),
    validate = { _, _, _ -> emptyList() },
    run = { explore() },
)

suspend fun BehaviourScope.explore() {
    val maxSystems = param("maxSystems")?.toIntOrNull() ?: 10
    val visited = linkedSetOf<String>()
    while (visited.size < maxSystems) {
        val system = me.nav.systemSymbol
        visited += system
        if (snapshot().waypointsIn(system).isEmpty()) phase("map", system) { loadSystem(system) }
        phase("chart", system) { chartSystem() }
        phase("read markets", system) { probeMarkets() }

        val gate = snapshot().waypointsIn(system).firstOrNull { it.type == WaypointType.JUMP_GATE }
        if (gate == null) { status("done", "$system has no gate; explored ${visited.size} systems"); return }
        // The cached waypoint flag goes stale the moment a gate completes; the site itself is the truth.
        if (gate.isUnderConstruction && !construction(gate.symbol).isComplete) { status("done", "${gate.symbol} is under construction; explored ${visited.size} systems"); return }
        phase("travel to gate", gate.symbol) { travelTo(gate.symbol) }
        val candidates = phase("choose next system", gate.symbol) {
            val connections = readGate(gate.symbol).connections.filter { it !in shared.unreachableGates }
            val fresh = connections.filter { it.substringBeforeLast('-') !in visited && snapshot().waypointsIn(it.substringBeforeLast('-')).isEmpty() }
            fresh + connections.filter { it.substringBeforeLast('-') !in visited && it !in fresh }
        }
        if (candidates.isEmpty()) { status("done", "every system beyond ${gate.symbol} is known or unreachable; explored ${visited.size}"); return }
        var arrived = false
        for (next in candidates) {
            arrived = phase("jump", "to $next") {
                try {
                    jump(ship, next)
                    status(detail = "arrived in ${next.substringBeforeLast('-')}; bank ${Intentions.format(agent().credits)}")
                    true
                } catch (e: VerbFailure.Api) {
                    if (e.error.code != DESTINATION_UNDER_CONSTRUCTION) throw e
                    // A gate needs both ends built; remember this one and take the next connection.
                    shared.unreachableGates += next
                    shared.editPlan("$next is unbuilt") { it.withUnbuilt(next) }
                    status(detail = "$next is under construction; trying the next connection")
                    false
                }
            }
            if (arrived) break
        }
        if (!arrived) { status("done", "every reachable system beyond ${gate.symbol} is known; explored ${visited.size}"); return }
    }
    status("done", "explored $maxSystems systems: ${visited.joinToString(", ")}")
}

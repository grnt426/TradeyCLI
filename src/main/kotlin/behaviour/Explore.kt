package behaviour

import behaviour.decisions.Intentions
import engine.VerbFailure
import model.system.WaypointType
import kotlin.time.Duration.Companion.minutes

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
        if (gate.isUnderConstruction) { status("done", "${gate.symbol} is under construction; explored ${visited.size} systems"); return }
        phase("travel to gate", gate.symbol) { travelTo(gate.symbol) }
        val next = phase("choose next system", gate.symbol) {
            val connections = jumpGate(gate.symbol).connections
            connections.firstOrNull { it.substringBeforeLast('-') !in visited && snapshot().waypointsIn(it.substringBeforeLast('-')).isEmpty() }
                ?: connections.firstOrNull { it.substringBeforeLast('-') !in visited }
        }
        if (next == null) { status("done", "every system beyond ${gate.symbol} is already known; explored ${visited.size}"); return }
        phase("jump", "to $next") {
            try {
                jump(ship, next)
                status(detail = "arrived in ${next.substringBeforeLast('-')}; bank ${Intentions.format(agent().credits)}")
            } catch (e: VerbFailure) {
                status(detail = "could not jump to $next: ${e.message}; trying again in 5 minutes")
                clock.sleep(5.minutes)
            }
        }
    }
    status("done", "explored $maxSystems systems: ${visited.joinToString(", ")}")
}

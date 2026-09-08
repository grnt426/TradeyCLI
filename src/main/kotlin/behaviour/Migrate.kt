package behaviour

import engine.VerbFailure
import model.system.WaypointType

/** A jump refused because the destination gate is still under construction. */
const val DESTINATION_UNDER_CONSTRUCTION = 4262

/**
 * Puts the ship in [system]: through the gate of the system it stands in, to the connected gate
 * there, buying the antimatter at the origin. Loads the destination's waypoints when they are
 * unknown. Returns false, and remembers the far gate as unreachable, when the jump is refused
 * because that gate is not built: a jump needs a finished gate at both ends.
 */
suspend fun BehaviourScope.goToSystem(system: String): Boolean {
    if (me.nav.systemSymbol == system) return true
    val from = me.nav.systemSymbol
    val gate = localGate(from) ?: throw BehaviourFailure("$from has no jump gate to reach $system through")
    // A neighbour is one jump; anything else is a route over the gates our ships have read, hop by hop.
    val path = phase("find the way", "to $system") {
        // A gate already in the map is not re-read: every migration read its local gate (430 reads an hour on 2026-09-07).
        if (shared.gates[gate.symbol] == null) readGate(gate.symbol)
        knowledge.GateGraph.route(shared.gates, gate.symbol, system, shared.unreachableGates)
    }
    if (path == null) { status(detail = "no known route from ${gate.symbol} to $system"); return false }
    phase("travel to gate", gate.symbol) { travelVia(gate.symbol) }
    path.forEachIndexed { i, target ->
        val jumped = phase("jump", "to $target" + (if (path.size > 1) " (${i + 1} of ${path.size})" else "")) {
            try {
                jump(ship, target); true
            } catch (e: VerbFailure.Api) {
                if (e.error.code != DESTINATION_UNDER_CONSTRUCTION) throw e
                shared.unreachableGates += target
                status(detail = "$target is under construction; $system cannot be reached this way")
                false
            }
        }
        if (!jumped) return false
        val here = target.substringBeforeLast('-')
        if (snapshot().waypointsIn(here).isEmpty()) phase("map", here) { loadSystem(here) }
        // Every gate passed through grows the map for the ships behind.
        if (here != system) runCatching { readGate(target) }
    }
    return true
}

/**
 * The jump gate of [system] from the loaded waypoints, or from the galaxy listing when the system's
 * waypoints were never loaded (four pioneers failed on "has no jump gate" in systems they had jumped
 * into, 2026-09-07), loading them on the way.
 */
suspend fun BehaviourScope.localGate(system: String): model.system.Waypoint? {
    snapshot().waypointsIn(system).firstOrNull { it.type == WaypointType.JUMP_GATE }?.let { return it }
    val listed = snapshot().systems[system]?.waypoints?.firstOrNull { it.type == WaypointType.JUMP_GATE } ?: return null
    runCatching { loadSystem(system) }
    return snapshot().waypointsIn(system).firstOrNull { it.type == WaypointType.JUMP_GATE } ?: snapshot().waypoints[listed.symbol]
}

/** Reads a gate and remembers its connections for [knowledge.GateGraph] routes. */
suspend fun BehaviourScope.readGate(symbol: String): model.responsebody.JumpGate =
    jumpGate(symbol).also { shared.gates[it.symbol] = it.connections }

/** The systems beyond the gate of [from] that no ship of ours has been refused at, nearest listed first. */
suspend fun BehaviourScope.reachableNeighbours(from: String): List<String> {
    val gate = snapshot().waypointsIn(from).firstOrNull { it.type == WaypointType.JUMP_GATE } ?: return emptyList()
    return readGate(gate.symbol).connections.filter { it !in shared.unreachableGates }.map { it.substringBeforeLast('-') }
}

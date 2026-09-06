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
    val gate = snapshot().waypointsIn(from).firstOrNull { it.type == WaypointType.JUMP_GATE }
        ?: throw BehaviourFailure("$from has no jump gate to reach $system through")
    val target = phase("find the way", "to $system") {
        jumpGate(gate.symbol).connections.firstOrNull { it.substringBeforeLast('-') == system }
    }
    // Not a neighbour of where the ship is now (it may have been diverted already): the caller picks another.
    if (target == null) { status(detail = "${gate.symbol} does not connect to $system"); return false }
    if (target in shared.unreachableGates) { status(detail = "$target is under construction; $system cannot be reached yet"); return false }
    phase("travel to gate", gate.symbol) { travelVia(gate.symbol) }
    val jumped = phase("jump", "to $target") {
        try {
            jump(ship, target); true
        } catch (e: VerbFailure.Api) {
            if (e.error.code != DESTINATION_UNDER_CONSTRUCTION) throw e
            shared.unreachableGates += target
            status(detail = "$target is under construction; $system cannot be reached yet")
            false
        }
    }
    if (!jumped) return false
    if (snapshot().waypointsIn(system).isEmpty()) phase("map", system) { loadSystem(system) }
    return true
}

/** The systems beyond the gate of [from] that no ship of ours has been refused at, nearest listed first. */
suspend fun BehaviourScope.reachableNeighbours(from: String): List<String> {
    val gate = snapshot().waypointsIn(from).firstOrNull { it.type == WaypointType.JUMP_GATE } ?: return emptyList()
    return jumpGate(gate.symbol).connections.filter { it !in shared.unreachableGates }.map { it.substringBeforeLast('-') }
}

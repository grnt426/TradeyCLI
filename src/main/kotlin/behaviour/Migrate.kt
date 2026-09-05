package behaviour

import model.system.WaypointType

/**
 * Puts the ship in [system]: through the gate of the system it stands in, to the connected gate
 * there, buying the antimatter at the origin. Loads the destination's waypoints when they are
 * unknown. A no-op when the ship is already there.
 */
suspend fun BehaviourScope.goToSystem(system: String) {
    if (me.nav.systemSymbol == system) return
    val from = me.nav.systemSymbol
    val gate = snapshot().waypointsIn(from).firstOrNull { it.type == WaypointType.JUMP_GATE }
        ?: throw BehaviourFailure("$from has no jump gate to reach $system through")
    phase("travel to gate", gate.symbol) { travelVia(gate.symbol) }
    val target = phase("find the way", "to $system") {
        jumpGate(gate.symbol).connections.firstOrNull { it.substringBeforeLast('-') == system }
            ?: throw BehaviourFailure("${gate.symbol} does not connect to $system")
    }
    phase("jump", "to $target") { jump(ship, target) }
    if (snapshot().waypointsIn(system).isEmpty()) phase("map", system) { loadSystem(system) }
}

package model.system

import kotlinx.serialization.Serializable
import model.WaypointTrait
import model.extension.LastRead
import model.faction.Faction

@Serializable
data class System(
    val sectorSymbol: String,
    val symbol: String,
    val type: String,
    val x: Long,
    val y: Long,
    val waypoints: List<SystemWaypoint> = emptyList(),
    val waypointTraits: List<WaypointTrait> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val factions: List<Faction>,
): LastRead()

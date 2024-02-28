package model.system

import kotlinx.serialization.Serializable
import model.WaypointTrait
import model.extension.LastRead
import model.faction.Faction

@Serializable
data class Waypoint(
    val systemSymbol: String,
    val symbol: String,
    val type: WaypointType,
    val x: Int,
    val y: Int,
    val orbitals: List<WaypointOrbital> = emptyList(),
    val traits: List<WaypointTrait> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val isUnderConstruction: Boolean,

    val chart: Chart? = null,
    val faction: Faction? = null,
    val orbits: String? = null,
) : LastRead()


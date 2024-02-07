package model.system

import model.Trait
import kotlinx.serialization.Serializable
import model.Faction

@Serializable
data class Waypoint(
    val systemSymbol: String,
    val symbol: String,
    val type: WaypointType,
    val x: Long,
    val y: Long,
    val orbitals: List<WaypointOrbital> = emptyList(),
    val traits: List<Trait> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val isUnderConstruction: Boolean,
    val chart: Chart? = null,
    val faction: Faction? = null,
    val orbits: String? = null,
)
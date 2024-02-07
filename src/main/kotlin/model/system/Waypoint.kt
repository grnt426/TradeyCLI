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
    val orbitals: List<OrbitingBodies> = emptyList(),
    val traits: List<Trait> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val orbits: String? = null,
    val isUnderConstruction: Boolean? = null,
    val chart: Chart? = null,
    val faction: Faction? = null
)

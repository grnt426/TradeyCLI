package model.system

import model.Trait
import kotlinx.serialization.Serializable

@Serializable
data class Waypoint(
    val symbol: String,
    val type: WaypointType,
    val x: Long,
    val y: Long,
    val orbitals: List<OrbitingBodies> = emptyList(),
    val traits: List<Trait> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val orbits: String? = null,
    val isUnderConstruction: Boolean? = null
)

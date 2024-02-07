package model.system

import kotlinx.serialization.Serializable

@Serializable
data class SystemWaypoint(
    val symbol: String,
    val type: WaypointType,
    val x: Long,
    val y: Long,
    val orbitals: List<WaypointOrbital>,
    val orbits: String? = null,
)
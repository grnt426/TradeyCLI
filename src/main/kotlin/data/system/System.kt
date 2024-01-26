package data.system

import data.Faction
import data.LastRead
import data.Trait
import kotlinx.serialization.Serializable

@Serializable
data class System(
    val sectorSymbol: String,
    val symbol: String,
    val type: String,
    val x: Long,
    val y: Long,
    val waypoints: List<Waypoint> = emptyList(),
    val traits: List<Trait> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val factions: List<Faction>,
): LastRead()

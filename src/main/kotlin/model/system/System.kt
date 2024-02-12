package model.system

import kotlinx.serialization.Serializable
import model.*
import model.faction.Faction

@Serializable
data class System(
    val sectorSymbol: String,
    val symbol: String,
    val type: String,
    val x: Long,
    val y: Long,
    val waypoints: List<SystemWaypoint> = emptyList(),
    val traits: List<Trait> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val factions: List<Faction>,
): LastRead()

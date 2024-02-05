package model.system

import model.Faction
import model.LastRead
import model.Trait
import model.TraitTypes
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

fun getShipyards(system: System): List<Waypoint> = system.waypoints.filter { w ->
    w.traits.firstOrNull { t -> t.symbol == TraitTypes.SHIPYARD } != null
}
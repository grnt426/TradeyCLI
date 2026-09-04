package model.system

import kotlinx.serialization.Serializable
import model.WaypointTrait
import model.WaypointTraitSymbol
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
    val modifiers: List<WaypointModifier> = emptyList(),
    val isUnderConstruction: Boolean = false,

    val chart: Chart? = null,
    val faction: Faction? = null,
    val orbits: String? = null,
) : LastRead() {
    fun hasTrait(trait: WaypointTraitSymbol): Boolean = traits.any { it.symbol == trait }
    fun hasModifier(symbol: String): Boolean = modifiers.any { it.symbol == symbol }
    val hasMarket: Boolean get() = hasTrait(WaypointTraitSymbol.MARKETPLACE)
    val hasShipyard: Boolean get() = hasTrait(WaypointTraitSymbol.SHIPYARD)
    val traitSymbols: Set<WaypointTraitSymbol> get() = traits.map { it.symbol }.toSet()

    /** Waypoint types a mining laser can work. */
    val isMineable: Boolean
        get() = type == WaypointType.ASTEROID || type == WaypointType.ENGINEERED_ASTEROID || type == WaypointType.ASTEROID_FIELD

    /** Waypoint types a gas siphon can work. */
    val isSiphonable: Boolean get() = type == WaypointType.GAS_GIANT

    val isCharted: Boolean get() = !hasTrait(WaypointTraitSymbol.UNCHARTED)
}

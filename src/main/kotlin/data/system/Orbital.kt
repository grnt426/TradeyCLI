package data.system

import data.Faction
import data.Trait
import kotlinx.serialization.Serializable

@Serializable
data class Orbital(
    val systemSymbol: String,
    val symbol: String,
    val type: String,
    val x: Long,
    val y: Long,
    val orbitals: List<Orbital> = emptyList(),
    val traits: List<Trait> = emptyList(),
    val modifiers: List<String> = emptyList(),
    val chart: Chart,
    val faction: Faction,
    val orbits: String,
    val isUnderConstruction: Boolean
)

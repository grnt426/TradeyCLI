package model.system

import client.SpaceTradersClient
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import model.*

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
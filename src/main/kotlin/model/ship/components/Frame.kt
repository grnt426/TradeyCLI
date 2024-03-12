package model.ship.components

import kotlinx.serialization.Serializable
import model.ship.Requirements

@Serializable
data class Frame(
    val symbol: String,
    val name: String,
    val description: String,
    val moduleSlots: Long,
    val mountingPoints: Long,
    val fuelCapacity: Long,
    val condition: Float? = null,
    val requirements: Requirements
)

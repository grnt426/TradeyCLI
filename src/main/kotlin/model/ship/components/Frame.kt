package model.ship.components

import model.ship.Requirements
import kotlinx.serialization.Serializable

@Serializable
data class Frame(
    val symbol: String,
    val name: String,
    val description: String,
    val moduleSlots: Long,
    val mountingPoints: Long,
    val fuelCapacity: Long,
    val condition: Long? = null,
    val requirements: Requirements
)

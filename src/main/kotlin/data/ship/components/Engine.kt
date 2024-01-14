package data.ship.components

import data.ship.Requirements
import kotlinx.serialization.Serializable

@Serializable
data class Engine(
    val symbol: String,
    val name: String,
    val description: String,
    val condition: Long,
    val speed: Long,
    val requirements: Requirements
)

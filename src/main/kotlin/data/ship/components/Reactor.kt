package data.ship.components

import data.ship.Requirements
import kotlinx.serialization.Serializable

@Serializable
data class Reactor(
    val symbol: String,
    val name: String,
    val description: String,
    val condition: Long,
    val powerOutput: Long,
    val requirements: Requirements
)

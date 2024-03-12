package model.ship.components

import kotlinx.serialization.Serializable
import model.ship.Requirements

@Serializable
data class Reactor(
    val symbol: String,
    val name: String,
    val description: String,
    val condition: Float? = null,
    val powerOutput: Long,
    val requirements: Requirements
)

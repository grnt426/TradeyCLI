package model.ship.components

import model.ship.Requirements
import kotlinx.serialization.Serializable

@Serializable
data class Reactor(
    val symbol: String,
    val name: String,
    val description: String,
    val condition: Long? = null,
    val powerOutput: Long,
    val requirements: Requirements
)

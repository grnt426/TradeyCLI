package model.ship.components

import model.ship.Requirements
import kotlinx.serialization.Serializable

@Serializable
data class Module(
    val symbol: String,
    val name: String,
    val description: String,
    val capacity: Long = 0,
    val requirements: Requirements
)

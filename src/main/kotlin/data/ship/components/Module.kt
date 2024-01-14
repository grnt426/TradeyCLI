package data.ship.components

import data.ship.Requirements
import kotlinx.serialization.Serializable

@Serializable
data class Module(
    val symbol: String,
    val name: String,
    val description: String,
    val capacity: Long = 0,
    val requirements: Requirements
)

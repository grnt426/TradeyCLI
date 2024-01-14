package data.ship.components

import kotlinx.serialization.Serializable

@Serializable
data class Cargo(
    val capacity: Long,
    val units: Long,
    val inventory: List<String>
)

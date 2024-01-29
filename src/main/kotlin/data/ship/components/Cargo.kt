package data.ship.components

import kotlinx.serialization.Serializable

@Serializable
data class Cargo(
    val capacity: Long,
    var units: Long,
    val inventory: List<String>
)

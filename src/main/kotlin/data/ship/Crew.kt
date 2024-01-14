package data.ship

import kotlinx.serialization.Serializable

@Serializable
data class Crew(
    val current: Long,
    val capacity: Long,
    val required: Long,
    val rotation: String,
    val morale: Long,
    val wages: Long
)

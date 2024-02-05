package model.ship

import kotlinx.serialization.Serializable

@Serializable
data class Crew(
    val current: Long? = null,
    val capacity: Long,
    val required: Long,
    val rotation: String? = null,
    val morale: Long? = null,
    val wages: Long? = null,
)

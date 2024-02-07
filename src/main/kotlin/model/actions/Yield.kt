package model.actions

import kotlinx.serialization.Serializable

@Serializable
data class Yield(
    val symbol: String,
    val units: Long,
)

package data.ship

import kotlinx.serialization.Serializable

@Serializable
data class Requirements(
    val power: Long = 0,
    val crew: Long = 0,
    val slots: Long = 0,
)

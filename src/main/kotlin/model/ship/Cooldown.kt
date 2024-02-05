package model.ship

import kotlinx.serialization.Serializable

@Serializable
data class Cooldown(
    val shipSymbol: String,
    val totalSeconds: Long,
    val remainingSeconds: Long
)

package data

import kotlinx.serialization.Serializable

@Serializable
class Agent(
    val accountId: String,
    val symbol: String,
    val headquarters: String,
    val credits: Long,
    val startingFaction: String,
    val shipCount: Long
)
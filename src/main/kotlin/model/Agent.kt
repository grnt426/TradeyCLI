package model

import kotlinx.serialization.Serializable
import model.extension.LastRead

@Serializable
data class Agent(
    /** Only present for your own agent, never for public agent listings. */
    val accountId: String? = null,
    val symbol: String,
    val headquarters: String,
    val credits: Long,
    val startingFaction: String,
    val shipCount: Long,
) : LastRead()

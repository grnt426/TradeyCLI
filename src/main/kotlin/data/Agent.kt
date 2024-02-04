package data

import kotlinx.serialization.Serializable

@Serializable
class Agent(
    val accountId: String,
    val symbol: String,
    val headquarters: String,
    val credits: Long,
    val startingFaction: String,
    val shipCount: Long,
) : LastRead()

fun hasCredits(amount: Long): Boolean = GameState.agent.credits >= amount

fun creditsBelow(amount: Long): Boolean = GameState.agent.credits < amount
package model

import kotlinx.serialization.Serializable
import model.extension.LastRead

@Serializable
class Agent(
    val accountId: String,
    val symbol: String,
    val headquarters: String,
    var credits: Long,
    val startingFaction: String,
    var shipCount: Long,
) : LastRead()

fun hasCredits(amount: Long): Boolean = GameState.agent.credits >= amount

fun creditsBelow(amount: Long): Boolean = GameState.agent.credits < amount

fun applyAgentUpdate(agent: Agent) {
    GameState.agent.credits = agent.credits
    GameState.agent.shipCount = agent.shipCount
}
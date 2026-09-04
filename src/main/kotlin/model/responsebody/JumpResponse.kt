package model.responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.market.MarketTransaction
import model.ship.Cooldown
import model.ship.Navigation

/** A jump through a gate: the ship is at the destination at once, on a cooldown, and a unit of antimatter was bought. */
@Serializable
data class JumpResponse(
    val nav: Navigation,
    val cooldown: Cooldown,
    val transaction: MarketTransaction? = null,
    val agent: Agent? = null,
)

@Serializable
data class JumpGate(
    val symbol: String,
    val connections: List<String> = emptyList(),
)

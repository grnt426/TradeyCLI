package responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.market.MarketTransaction
import model.ship.components.Cargo

@Serializable
data class SellCargoResponse(
    val agent: Agent,
    val cargo: Cargo,
    val transaction: MarketTransaction,
)

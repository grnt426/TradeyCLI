package responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.Transaction
import model.ship.components.Fuel

@Serializable
data class RefuelResponse(
    val agent: Agent,
    val fuel: Fuel,
    val transaction: Transaction,
)

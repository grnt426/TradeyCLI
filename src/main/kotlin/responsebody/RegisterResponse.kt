package responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.faction.Faction
import model.contract.Contract
import model.ship.Ship

@Serializable
data class RegisterResponse(
    val agent: Agent,
    val contract: Contract,
    val faction: Faction,
    val ship: Ship,
    var token: String,
)

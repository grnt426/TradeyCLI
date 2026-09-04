package model.responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.contract.Contract
import model.faction.Faction
import model.ship.Ship

@Serializable
data class RegisterResponse(
    val agent: Agent,
    val contract: Contract,
    val faction: Faction,

    /** Starting fleet. As of API 2.3 this is the command ship plus a probe. */
    val ships: List<Ship>,
    var token: String,
)

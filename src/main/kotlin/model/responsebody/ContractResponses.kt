package model.responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.contract.Contract
import model.ship.components.Cargo

@Serializable
data class ContractResponse(
    val contract: Contract,
    val agent: Agent? = null,
)

@Serializable
data class DeliverResponse(
    val contract: Contract,
    val cargo: Cargo,
)

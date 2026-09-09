package model.responsebody

import kotlinx.serialization.Serializable
import model.Agent
import model.extension.InstantSerializer
import model.ship.components.Cargo
import model.ship.components.Module
import java.time.Instant

/** What the server answers to a module install or removal: the ship's modules and hold after it, and the yard's fee. */
@Serializable
data class ShipModuleResponse(
    val agent: Agent,
    val modules: List<Module>,
    val cargo: Cargo,
    val transaction: ShipModificationTransaction,
)

@Serializable
data class ShipModificationTransaction(
    val waypointSymbol: String,
    val shipSymbol: String,
    val tradeSymbol: String,
    val totalPrice: Long,
    @Serializable(with = InstantSerializer::class) val timestamp: Instant,
)

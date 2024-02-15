package model.responsebody

import kotlinx.serialization.Serializable
import model.ship.components.Cargo

@Serializable
data class TransferResponse(
    val cargo: Cargo
)

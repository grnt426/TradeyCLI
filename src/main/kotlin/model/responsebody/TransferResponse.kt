package model.responsebody

import kotlinx.serialization.Serializable
import model.ship.components.Cargo

@Serializable
data class TransferResponse(
    val cargo: Cargo,
    /** The receiving ship's hold after the transfer; the live API sends it, the simulator too. */
    val targetCargo: Cargo? = null,
)

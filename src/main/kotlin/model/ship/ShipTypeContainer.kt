package model.ship

import kotlinx.serialization.Serializable

@Serializable
data class ShipTypeContainer(
    val type: ShipType
)

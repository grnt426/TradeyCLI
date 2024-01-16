package data.ship

import kotlinx.serialization.Serializable

@Serializable
enum class ShipType {
    SHIP_MINING_DRONE,
    SHIP_SURVEYOR
}
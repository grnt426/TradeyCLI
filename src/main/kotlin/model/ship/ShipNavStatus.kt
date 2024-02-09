package model.ship

import kotlinx.serialization.Serializable

@Serializable
enum class ShipNavStatus {
    IN_TRANSIT,
    IN_ORBIT,
    DOCKED
}
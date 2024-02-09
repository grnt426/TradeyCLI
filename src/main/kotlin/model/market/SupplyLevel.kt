package model.market

import kotlinx.serialization.Serializable

@Serializable
enum class SupplyLevel {
    SCARCE,
    LIMITED,
    MODERATE,
    HIGH,
    ABUNDANT
}
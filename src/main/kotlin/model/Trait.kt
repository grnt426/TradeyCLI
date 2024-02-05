package model

import kotlinx.serialization.Serializable

@Serializable
data class Trait(
    val symbol: TraitTypes,
    val name: String,
    val description: String,
)

enum class TraitTypes {
    SHIPYARD,
    MARKETPLACE,
    INDUSTRIAL,
    TOXIC_ATMOSPHERE,
    STRONG_MAGNETOSPHERE,
    EXTREME_PRESSURE,
    SCATTERED_SETTLEMENTS,
    VOLCANIC
}
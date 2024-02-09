package model.market

import kotlinx.serialization.Serializable

@Serializable
enum class ActivityLevel {
    WEAK,
    GROWING,
    STRONG,
    RESTRICTED
}
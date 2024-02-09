package model.market

import kotlinx.serialization.Serializable

@Serializable
enum class TradeGoodType {
    EXPORT,
    IMPORT,
    EXCHANGE
}

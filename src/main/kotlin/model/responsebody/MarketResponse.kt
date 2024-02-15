package model.responsebody

import kotlinx.serialization.Serializable
import model.market.Market

@Serializable
data class MarketResponse(
    val data: Market
)

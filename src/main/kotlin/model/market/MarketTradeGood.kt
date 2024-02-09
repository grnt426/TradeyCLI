package model.market

import kotlinx.serialization.Serializable

@Serializable
data class MarketTradeGood(
    val symbol: TradeSymbol,
    val type: TradeGoodType,
    val tradeVolume: Int,
    val supply: SupplyLevel,
    val purchasePrice: Int,
    val sellPrice: Int,

    val activity: ActivityLevel? = null,
)

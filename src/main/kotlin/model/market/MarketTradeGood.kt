package model.market

import kotlinx.serialization.Serializable

@Serializable
data class MarketTradeGood(
    val symbol: TradeSymbol,
    val type: TradeGoodType,
    val tradeVolume: Int,
    val supply: SupplyLevel,

    /**
     * The amount each unit can be purchased from the Market to the Agent
     */
    val purchasePrice: Int,

    /**
     * The amount each unit can be sold From the Agent to the Market
     */
    val sellPrice: Int,

    val activity: ActivityLevel? = null,
)

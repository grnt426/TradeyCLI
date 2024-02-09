package requestbody

import kotlinx.serialization.Serializable
import model.market.TradeSymbol

@Serializable
data class SellCargoRequest(
    val symbol: TradeSymbol,
    val units: Int,
)

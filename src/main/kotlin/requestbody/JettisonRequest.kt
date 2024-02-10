package requestbody

import kotlinx.serialization.Serializable
import model.market.TradeSymbol

@Serializable
data class JettisonRequest(
    val symbol: TradeSymbol,
    val units: Int,
)

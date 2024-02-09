package requestbody

import kotlinx.serialization.Serializable
import model.TradeSymbol

@Serializable
data class CargoTransferRequest(
    val tradeSymbol: TradeSymbol,
    val units: Int,
    val shipSymbol: String,
)
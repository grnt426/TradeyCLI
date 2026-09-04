package model.requestbody

import kotlinx.serialization.Serializable

@Serializable
data class ContractDeliverRequest(
    val shipSymbol: String,
    val tradeSymbol: String,
    val units: Int,
)

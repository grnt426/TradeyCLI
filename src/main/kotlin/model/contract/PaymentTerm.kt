package model.contract

import kotlinx.serialization.Serializable

@Serializable
data class PaymentTerm(
    val onAccepted: Long,
    val onFulfilled: Long,
)

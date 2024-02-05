package model.contract

import kotlinx.serialization.Serializable

@Serializable
data class ContractTerms(
    val deadline: String,
    val payment: PaymentTerm,
    val deliver: List<DeliverTerm>,
)

package data.contract

import kotlinx.serialization.Serializable

@Serializable
data class Contract(
    val id: String,
    val factionSymbol: String,
    val type: String,
    val terms: ContractTerms,
    val accepted: Boolean,
    val fulfilled: Boolean,
    val expiration: String,
    val deadlineToAccept: String,
)

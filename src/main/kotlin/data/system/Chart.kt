package data.system

import kotlinx.serialization.Serializable

@Serializable
data class Chart(
    val submittedBy: String,
    val submittedOn: String,
)

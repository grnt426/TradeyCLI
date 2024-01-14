package data

import kotlinx.serialization.Serializable

@Serializable
data class Trait(
    val symbol: String,
    val name: String,
    val description: String,
)

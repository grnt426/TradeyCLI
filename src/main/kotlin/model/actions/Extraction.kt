package model.actions

import kotlinx.serialization.Serializable

@Serializable
data class Extraction(
    val shipSymbol: String,
    val yield: Yield,
)

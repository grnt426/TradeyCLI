package model.faction

import kotlinx.serialization.Serializable

@Serializable
data class FactionTrait(
    val symbol: FactionTraitType,
    val name: String,
    val description: String,
)
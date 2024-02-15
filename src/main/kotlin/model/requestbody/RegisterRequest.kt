package model.requestbody

import kotlinx.serialization.Serializable
import model.faction.FactionSymbol

@Serializable
data class RegisterRequest(
    val symbol: String,
    val faction: FactionSymbol,
)

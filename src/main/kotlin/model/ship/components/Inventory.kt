package model.ship.components

import Symbol
import kotlinx.serialization.Serializable

@Serializable
data class Inventory(
    override val symbol: String,
    val name: String,
    val description: String,
    val units: Long,
): Symbol

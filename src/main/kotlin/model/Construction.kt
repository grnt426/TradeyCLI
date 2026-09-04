package model

import kotlinx.serialization.Serializable
import model.market.TradeSymbol
import model.ship.components.Cargo

/** A waypoint being built, such as the home system's jump gate, and what it still needs. */
@Serializable
data class Construction(
    val symbol: String,
    val materials: List<ConstructionMaterial>,
    val isComplete: Boolean,
) {
    fun remaining(good: TradeSymbol): Long = materials.firstOrNull { it.tradeSymbol == good }?.let { it.required - it.fulfilled } ?: 0
    val outstanding: List<ConstructionMaterial> get() = materials.filter { it.fulfilled < it.required }
}

@Serializable
data class ConstructionMaterial(
    val tradeSymbol: TradeSymbol,
    val required: Long,
    val fulfilled: Long,
)

@Serializable
data class SupplyConstructionResponse(
    val construction: Construction,
    val cargo: Cargo,
)

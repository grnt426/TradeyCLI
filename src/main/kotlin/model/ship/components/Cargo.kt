package model.ship.components

import kotlinx.serialization.Serializable
import model.market.TradeSymbol

@Serializable
data class Cargo(
    val capacity: Int,
    val units: Int,
    val inventory: List<Inventory> = emptyList(),
) {
    val isFull: Boolean get() = units >= capacity
    val isEmpty: Boolean get() = units == 0
    val spaceLeft: Int get() = capacity - units
    val fillRatio: Double get() = if (capacity == 0) 0.0 else units.toDouble() / capacity

    fun unitsOf(good: TradeSymbol): Int = inventory.firstOrNull { it.symbol == good }?.units ?: 0

    /** A copy with [delta] units of [good] added (or removed when negative); drops the line at zero. */
    fun adjusted(good: TradeSymbol, delta: Int, name: String = good.name): Cargo {
        val existing = inventory.firstOrNull { it.symbol == good }
        val newUnits = (existing?.units ?: 0) + delta
        require(newUnits >= 0) { "Cannot remove ${-delta} $good from ${existing?.units ?: 0}" }
        val rest = inventory.filterNot { it.symbol == good }
        val lines = if (newUnits == 0) rest else rest + Inventory(good, existing?.name ?: name, existing?.description ?: "", newUnits)
        return copy(units = lines.sumOf { it.units }, inventory = lines.sortedBy { it.symbol.name })
    }
}

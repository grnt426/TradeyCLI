package behaviour.decisions

import engine.Snapshot
import engine.Travel
import knowledge.DefaultPrices
import model.market.Market
import model.ship.components.Cargo
import model.ship.components.Inventory
import model.system.Waypoint

object Selling {
    /** What to sell here and what to drop, most valuable first so a full order book takes the best lines. */
    fun split(cargo: Cargo, market: Market): Pair<List<Inventory>, List<Inventory>> {
        val (sell, drop) = cargo.inventory.partition { market.trades(it.symbol) }
        return sell.sortedByDescending { (market.sellPriceOf(it.symbol) ?: 0) * it.units } to drop
    }

    /** Value of [cargo] at [market], using guesses where prices are unknown. */
    fun valueAt(cargo: Cargo, market: Market): Long = cargo.inventory.sumOf { line ->
        val price = market.sellPriceOf(line.symbol) ?: market.typeOf(line.symbol)?.let { DefaultPrices.sell(line.symbol, it) } ?: 0
        price.toLong() * line.units
    }

    /** The market in the ship's system where [cargo] fetches the most after fuel, or null if none buys any of it. */
    fun bestMarketFor(cargo: Cargo, from: Waypoint, snapshot: Snapshot, creditsPerFuelUnit: Double = 0.72): Market? =
        snapshot.marketsIn(from.systemSymbol)
            .mapNotNull { market ->
                val w = snapshot.waypoints[market.symbol] ?: return@mapNotNull null
                val value = valueAt(cargo, market)
                if (value == 0L) return@mapNotNull null
                val fuel = Travel.fuelCost(Travel.distance(from.x, from.y, w.x, w.y), model.ship.FlightMode.CRUISE)
                market to (value - fuel * creditsPerFuelUnit)
            }
            .maxByOrNull { it.second }
            ?.first
}

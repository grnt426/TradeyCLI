package behaviour.decisions

import engine.Snapshot
import engine.Travel
import model.market.Market
import model.market.TradeSymbol
import model.ship.FlightMode
import model.ship.Ship
import model.system.Waypoint
import java.time.Instant
import knowledge.MarketAssumptions
import knowledge.MarketHealth
import kotlin.math.min

/** Buy [good] at [source], sell it at [destination], and what that should pay. */
data class TradePlan(
    val good: TradeSymbol,
    val source: Market,
    val destination: Market,
    val buyPrice: Int,
    val sellPrice: Int,
    /** Units to carry: the hold, the money, and how far the prices can move before the margin is gone. */
    val units: Int,
    /** Expected profit for the load after the prices move against us and fuel is paid. */
    val profit: Long,
    /** Seconds from where the ship is, through the source, to the destination, with overheads. */
    val cycleSeconds: Long,
    val creditsPerHour: Double,
    val legToSource: Double,
    val legToDestination: Double,
    /** Credits per hour weighted by how healthy the buy and the sell are for the markets (see [knowledge.MarketAssumptions]). */
    val score: Double = creditsPerHour,
    /** The listings' supply/activity at both ends, for the eye. */
    val health: String = "",
    /** The load delivers a short input to one of the gate's producers: any positive margin is worth it. */
    val feeds: Boolean = false,
) {
    val marginPerUnit: Int get() = sellPrice - buyPrice
    fun summary(): String =
        "$good ${source.symbol} @$buyPrice -> ${destination.symbol} @$sellPrice: $units units, ~$profit profit, ~${creditsPerHour.toInt()} cr/h, cycle ${cycleSeconds / 60}m"
}

data class TradingAssumptions(
    /** How prices move against us per trade volume, observed live on 2026-09-04: selling starts at 2.1% and grows 30% a volume; buying starts at 0.56% and grows 22%. */
    val sellImpactPerVolume: Double = 0.021,
    val sellImpactGrowth: Double = 1.3,
    val buyImpactPerVolume: Double = 0.0056,
    val buyImpactGrowth: Double = 1.22,
    /** Readings older than this are not trusted for a purchase decision. */
    val maxPriceAge: java.time.Duration = java.time.Duration.ofHours(6),
    /** Never spend more than this share of the bank on one load. */
    val capitalShare: Double = 0.8,
    /** Seconds for docking, buying, selling and refueling per cycle. */
    val overheadSeconds: Long = 30,
    val creditsPerFuelUnit: Double = 0.72,
    /** Below this margin per unit, after impact, a pair is not worth the trip. */
    val minMarginPerUnit: Int = 20,
    /**
     * The steady-state rule: stop buying a batch once its margin falls below this share of its
     * buy price, and skip a route already below it. Prices in this system do not recover on the
     * hour scale (measured 2026-09-04), so taking the last few percent of a spread only empties
     * the market for good; leaving 15% on the table keeps the route alive.
     */
    val minMarginRatio: Double = 0.15,
    /** How the listings' type, supply and activity weight a route; the knowledge lives in [knowledge.MarketAssumptions]. */
    val market: MarketAssumptions = MarketAssumptions(),
    /** "market/good" pairs that are short inputs of the gate's producers: delivering there earns [MarketAssumptions.chainFeedBonus]. */
    val chainTargets: Set<String> = emptySet(),
    /** "market/good" exports of gate-chain producers whose inputs are short: not a source while [MarketAssumptions.protectStarvedChains]. */
    val protectedSources: Set<String> = emptySet(),
    /** "market/good" exports of gate-chain producers: at [MarketAssumptions.reserveChainExportsBelow] or worse they go only to [chainTargets]. */
    val chainSources: Set<String> = emptySet(),
) {
    /** The smallest margin worth having on a unit bought at [buyPrice]. */
    fun floor(buyPrice: Double): Double = maxOf(minMarginPerUnit.toDouble(), buyPrice * minMarginRatio)

    /** The same rules with the floor down to break-even, for a load that feeds a gate producer. */
    fun forFeeding(): TradingAssumptions = copy(minMarginPerUnit = 1, minMarginRatio = 0.0)
}

object Trading {

    /**
     * Every pair of markets with observed prices where a good is cheaper to buy at one than it
     * sells for at the other, scored by credits per hour from where [ship] is now. Prices move as
     * we buy and sell, so the load is sized to what still pays and the profit is discounted for it.
     */
    fun rank(snapshot: Snapshot, ship: Ship, now: Instant, assumptions: TradingAssumptions = TradingAssumptions()): List<TradePlan> {
        val capacity = ship.cargo.capacity.takeIf { it > 0 } ?: return emptyList()
        val credits = snapshot.agent?.credits ?: return emptyList()
        val here = snapshot.waypoints[ship.nav.waypointSymbol] ?: return emptyList()
        val fresh = now.minus(assumptions.maxPriceAge)
        val markets = snapshot.pricedMarketsIn(ship.nav.systemSymbol).filter { !it.lastRead.isBefore(fresh) }
        val plans = mutableListOf<TradePlan>()
        for (source in markets) {
            val sourceWaypoint = snapshot.waypoints[source.symbol] ?: continue
            val legToSource = Travel.distance(here.x, here.y, sourceWaypoint.x, sourceWaypoint.y)
            if (ship.usesFuel && Travel.fuelCost(legToSource, FlightMode.CRUISE) > ship.fuel.capacity) continue
            for (offer in source.tradeGoods) {
                val sourceWeight = MarketHealth.sourceWeight(offer, assumptions.market)
                if (sourceWeight <= 0.0) continue
                if (assumptions.market.protectStarvedChains && "${source.symbol}/${offer.symbol.name}" in assumptions.protectedSources) continue
                for (destination in markets) {
                    if (destination.symbol == source.symbol) continue
                    val bid = destination.good(offer.symbol) ?: continue
                    val destinationWeight = MarketHealth.destinationWeight(bid, assumptions.market)
                    if (destinationWeight <= 0.0) continue
                    val feeds = "${destination.symbol}/${offer.symbol.name}" in assumptions.chainTargets
                    if (!feeds && offer.supply <= assumptions.market.reserveChainExportsBelow && "${source.symbol}/${offer.symbol.name}" in assumptions.chainSources) continue
                    val rules = if (feeds) assumptions.forFeeding() else assumptions
                    if (bid.sellPrice - offer.purchasePrice < rules.floor(offer.purchasePrice.toDouble())) continue
                    val destinationWaypoint = snapshot.waypoints[destination.symbol] ?: continue
                    val legToDestination = Travel.distance(sourceWaypoint.x, sourceWaypoint.y, destinationWaypoint.x, destinationWaypoint.y)
                    if (ship.usesFuel && Travel.fuelCost(legToDestination, FlightMode.CRUISE) > ship.fuel.capacity) continue
                    val load = sizeLoad(offer.purchasePrice, offer.tradeVolume, bid.sellPrice, bid.tradeVolume, capacity, (credits * assumptions.capitalShare).toLong(), rules)
                    if (load.units <= 0) continue
                    val fuel = if (ship.usesFuel) Travel.fuelCost(legToSource, FlightMode.CRUISE) + Travel.fuelCost(legToDestination, FlightMode.CRUISE) else 0L
                    val profit = load.profit - (fuel * assumptions.creditsPerFuelUnit).toLong()
                    if (profit <= 0) continue
                    val seconds = (if (legToSource > 0) Travel.seconds(legToSource, FlightMode.CRUISE, ship.engine.speed) else 0L) +
                        Travel.seconds(legToDestination, FlightMode.CRUISE, ship.engine.speed) + assumptions.overheadSeconds
                    val perHour = profit.toDouble() / seconds * 3600
                    plans += TradePlan(
                        offer.symbol, source, destination, offer.purchasePrice, bid.sellPrice, load.units, profit, seconds, perHour, legToSource, legToDestination,
                        score = perHour * sourceWeight * destinationWeight * (if (feeds) assumptions.market.chainFeedBonus else 1.0),
                        health = "${offer.type.name.lowercase()} ${MarketHealth.describe(offer)} -> ${bid.type.name.lowercase()} ${MarketHealth.describe(bid)}" + (if (feeds) " (feeds the gate)" else ""),
                        feeds = feeds,
                    )
                }
            }
        }
        return plans.sortedByDescending { it.score }
    }

    data class Load(val units: Int, val cost: Long, val revenue: Long) {
        val profit: Long get() = revenue - cost
    }

    /**
     * How many units to carry when each trade volume bought raises the price and each sold lowers
     * it. Adds one volume at a time while the marginal batch still pays and money and hold allow.
     */
    fun sizeLoad(buyPrice: Int, buyVolume: Int, sellPrice: Int, sellVolume: Int, capacity: Int, budget: Long, assumptions: TradingAssumptions = TradingAssumptions()): Load {
        var units = 0
        var cost = 0L
        var revenue = 0L
        var buyAt = buyPrice.toDouble()
        var sellAt = sellPrice.toDouble()
        var boughtVolumes = 0.0
        var soldVolumes = 0.0
        val step = min(buyVolume, sellVolume).coerceAtLeast(1)
        while (units < capacity) {
            val batch = min(step, capacity - units)
            if (sellAt - buyAt < assumptions.floor(buyAt)) break
            val batchCost = (buyAt * batch).toLong()
            if (cost + batchCost > budget) break
            units += batch
            cost += batchCost
            revenue += (sellAt * batch).toLong()
            // the next batch pays more and fetches less, and each one more so
            buyAt *= 1 + assumptions.buyImpactPerVolume * Math.pow(assumptions.buyImpactGrowth, boughtVolumes) * batch / buyVolume
            sellAt *= 1 - assumptions.sellImpactPerVolume * Math.pow(assumptions.sellImpactGrowth, soldVolumes) * batch / sellVolume
            boughtVolumes += batch.toDouble() / buyVolume
            soldVolumes += batch.toDouble() / sellVolume
        }
        return Load(units, cost, revenue)
    }

    /** The plan's margin re-checked against the source's live prices; null when it no longer pays. */
    fun stillPays(plan: TradePlan, source: Market, assumptions: TradingAssumptions = TradingAssumptions()): Int? {
        val live = source.good(plan.good)?.purchasePrice ?: return null
        val margin = plan.sellPrice - live
        return margin.takeIf { it >= assumptions.floor(live.toDouble()) }
    }
}

package behaviour

import behaviour.decisions.Selling
import behaviour.decisions.TradePlan
import behaviour.decisions.Trading
import behaviour.decisions.TradingAssumptions
import engine.Travel
import engine.VerbFailure
import model.market.Market
import model.ship.FlightMode
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Buy where a good is cheap, sell where it is dear, repeat. Every cycle it ranks every pair of
 * markets with fresh prices from where the ship is, so it follows the prices the probe and its
 * own visits keep reading. A pair whose margin has gone by the time the ship arrives is set aside
 * for a while. Whatever is in the hold at the start is sold first.
 */
val tradeSpec = BehaviourSpec(
    name = "trade",
    description = "Haul goods between markets: buy at the cheap one, sell at the dear one, re-plan every load.",
    params = listOf(
        ParamSpec("good", "Only trade this good"),
        ParamSpec("minMargin", "Minimum credits per unit after prices move (default 20)"),
        ParamSpec("minMarginRatio", "Stop a route once the margin drops below this share of the buy price (default 0.15)"),
    ),
    validate = { _, ship, _ -> buildList { if (ship.cargo.capacity == 0) add("${ship.symbol} has no cargo hold") } },
    run = { trade() },
)

suspend fun BehaviourScope.trade() {
    val onlyGood = param("good")?.uppercase()
    val assumptions = TradingAssumptions(
        minMarginPerUnit = param("minMargin")?.toIntOrNull() ?: 20,
        minMarginRatio = param("minMarginRatio")?.toDoubleOrNull() ?: 0.15,
    )
    val setAside = mutableMapOf<String, Instant>()

    if (!me.cargo.isEmpty) phase("sell leftovers") { sellLeftovers() }

    while (true) {
        clock.sleep(1.seconds)
        val now = clock.now()
        setAside.entries.removeIf { it.value.isBefore(now) }
        shared.releaseRoutes(ship)
        val plan = phase("plan") {
            Trading.rank(snapshot(), me, now, assumptions)
                .filter { onlyGood == null || it.good.name == onlyGood }
                .filter { "${it.good}:${it.source.symbol}:${it.destination.symbol}" !in setAside }
                // Another trader working this good at either end would eat our margin: take the next best route.
                .filter { !shared.routeTakenByOther("${it.good}@${it.source.symbol}", ship) && !shared.routeTakenByOther("${it.good}@${it.destination.symbol}", ship) }
                .firstOrNull()
        }
        if (plan == null) {
            // Nothing pays with the prices known right now; a probe may be reading more. Wait, do not fail.
            status("waiting", "no profitable trade between markets with fresh prices" + (onlyGood?.let { " for $it" } ?: "") + "; checking again in 5 minutes")
            clock.sleep(5.minutes)
            continue
        }
        status("plan", plan.summary())
        shared.claimRoute(ship, "${plan.good}@${plan.source.symbol}", "${plan.good}@${plan.destination.symbol}")
        val key = "${plan.good}:${plan.source.symbol}:${plan.destination.symbol}"

        phase("travel to source", plan.source.symbol) {
            ensureFuel(Travel.fuelCost(plan.legToSource, FlightMode.CRUISE) + 10)
            travelTo(plan.source.symbol)
        }

        val bought = phase("buy", "${plan.good} at ${plan.source.symbol}") {
            dock(ship)
            val live = refreshMarket(plan.source.symbol)
            val margin = Trading.stillPays(plan, live, assumptions)
            if (margin == null) {
                status(detail = "${plan.good} at ${plan.source.symbol} no longer pays; setting the pair aside")
                setAside[key] = now.plusSeconds(30.minutes.inWholeSeconds)
                0
            } else {
                buyLoad(plan, live, assumptions)
            }
        }
        if (bought == 0) continue

        phase("travel to destination", plan.destination.symbol) {
            ensureFuel(Travel.fuelCost(plan.legToDestination, FlightMode.CRUISE) + 10)
            travelTo(plan.destination.symbol)
        }

        phase("sell", "${plan.good} at ${plan.destination.symbol}") {
            dock(ship)
            val market = refreshMarket(plan.destination.symbol)
            val (toSell, toDrop) = Selling.split(me.cargo, market)
            var earned = 0L
            toSell.forEach { line ->
                earned += sell(ship, line.symbol, line.units).credits
                status(detail = "sold ${line.units} ${line.symbol}; +$earned this load")
            }
            toDrop.forEach { line -> jettison(ship, line.symbol, line.units) }
        }

        phase("refuel", "at ${plan.destination.symbol}") {
            try {
                refuel(ship)
            } catch (e: VerbFailure) {
                status(detail = "could not refuel: ${e.message}")
            }
            refreshMarket(plan.destination.symbol)
        }

        if (here.hasShipyard && shared.goals.fleet.isNotEmpty()) phase("expand", "at ${here.symbol}") { maybeExpand() }
    }
}

/** Buys one trade volume at a time while the live price still leaves the margin. Returns units bought. */
private suspend fun BehaviourScope.buyLoad(plan: TradePlan, market: Market, assumptions: TradingAssumptions): Int {
    var current = market
    var bought = 0
    var spent = 0L
    while (true) {
        val offer = current.good(plan.good) ?: break
        val space = me.cargoSpaceLeft
        val affordable = ((agent().credits * assumptions.capitalShare) / offer.purchasePrice).toInt()
        val batch = minOf(offer.tradeVolume, space, affordable, plan.units - bought)
        if (batch <= 0) break
        if (plan.sellPrice - offer.purchasePrice < assumptions.floor(offer.purchasePrice.toDouble())) break
        val purchase = try {
            purchase(ship, plan.good, batch)
        } catch (e: VerbFailure.NotEnoughCredits) {
            break
        }
        bought += purchase.units
        spent += purchase.credits
        status(detail = "bought $bought ${plan.good} for $spent (last at ${purchase.averagePrice.toInt()}, sells ${plan.sellPrice})")
        if (purchase.units < batch) break
        current = refreshMarket(plan.source.symbol)
    }
    return bought
}

/** Sells what the hold carries at the market that pays best for it, dropping what nothing buys. */
private suspend fun BehaviourScope.sellLeftovers() {
    val market = Selling.bestMarketFor(me.cargo, here, snapshot())
    if (market == null) {
        me.cargo.inventory.forEach { jettison(ship, it.symbol, it.units) }
        return
    }
    travelTo(market.symbol)
    dock(ship)
    val live = refreshMarket(market.symbol)
    val (toSell, toDrop) = Selling.split(me.cargo, live)
    toSell.forEach { sell(ship, it.symbol, it.units) }
    toDrop.forEach { jettison(ship, it.symbol, it.units) }
}

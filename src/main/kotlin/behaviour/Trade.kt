package behaviour

import behaviour.decisions.Selling
import behaviour.decisions.TradePlan
import behaviour.decisions.Tour
import behaviour.decisions.Trading
import behaviour.decisions.TradingAssumptions
import engine.Travel
import engine.VerbFailure
import model.market.Market
import model.ship.FlightMode
import java.time.Duration
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
        ParamSpec("system", "Trade in this system: jump there through the gate first, and read its markets if nobody has"),
        ParamSpec("garden", "off to wait when no route pays instead of feeding the system's most starved importer (default on)"),
        ParamSpec("reserve", "Credits gardening never spends below (default 200000)"),
    ),
    validate = { _, ship, _ -> buildList { if (ship.cargo.capacity == 0) add("${ship.symbol} has no cargo hold") } },
    run = { trade() },
)

suspend fun BehaviourScope.trade() {
    val onlyGood = param("good")?.uppercase()
    var assumptions = knowledge.Strategy.trading(shared.plan.phase, param("minMargin")?.toIntOrNull(), param("minMarginRatio")?.toDoubleOrNull(), snapshot())
    val setAside = mutableMapOf<String, Instant>()

    if (!me.cargo.isEmpty) phase("sell leftovers") { sellLeftovers() }
    var lastYardDetour: Instant? = null
    param("system")?.uppercase()?.let { wanted ->
        if (me.nav.systemSymbol != wanted) phase("migrate", "to $wanted") {
            if (!goToSystem(wanted)) {
                val taken = shared.plan.assignments.filter { it.ship != ship }.mapNotNull { it.params["system"]?.uppercase() }.toSet()
                val alternative = reachableNeighbours(me.nav.systemSymbol).filter { it != wanted }.sortedBy { if (it in taken) 1 else 0 }.firstOrNull { goToSystem(it) }
                status(detail = if (alternative != null) "$wanted is unreachable; trading in $alternative instead" else "$wanted is unreachable and so is every other neighbour; trading here")
                // The choice sticks: A and B bounced four jumps back toward the system they could not reach once a route appeared (2026-09-07).
                val settled = alternative ?: me.nav.systemSymbol
                shared.editPlan("$ship trades in $settled instead of $wanted") { p -> p.assignmentFor(ship)?.let { a -> p.with(a.copy(params = a.params + ("system" to settled))) } ?: p }
            }
        }
    }
    var surveyed = false

    while (true) {
        clock.sleep(1.seconds)
        val now = clock.now()
        setAside.entries.removeIf { it.value.isBefore(now) }
        shared.releaseRoutes(ship)
        // The gate's short inputs change as the chains move; re-read them for every plan.
        assumptions = knowledge.Strategy.trading(shared.plan.phase, param("minMargin")?.toIntOrNull(), param("minMarginRatio")?.toDoubleOrNull(), snapshot())
        val plan = phase("plan") {
            Trading.rank(snapshot(), me, now, assumptions)
                .filter { onlyGood == null || it.good.name == onlyGood }
                .filter { "${it.good}:${it.source.symbol}:${it.destination.symbol}" !in setAside }
                // Another trader working this good at either end would eat our margin: take the next best route.
                .filter { !shared.routeTakenByOther("${it.good}@${it.source.symbol}", ship) && !shared.routeTakenByOther("${it.good}@${it.destination.symbol}", ship) }
                .firstOrNull()
                // Claim before anything suspends, or two traders planning at once both take the same route.
                ?.also { shared.claimRoute(ship, "${it.good}@${it.source.symbol}", "${it.good}@${it.destination.symbol}") }
        }
        if (plan == null) {
            // In a system nobody of ours has read, read it once ourselves before waiting on a probe.
            val system = me.nav.systemSymbol
            if (!surveyed && snapshot().pricedMarketsIn(system).size < 3) {
                surveyed = true
                phase("survey", system) { surveyMarkets(system) }
                continue
            }
            // Nothing pays with the prices known right now. Spend the idle time growing a market: feed the hungriest importer.
            if (param("garden") != "off" && gardenOnce(param("reserve")?.toLongOrNull() ?: 200_000L, assumptions.market, "trade")) continue
            // Nothing to feed either; a probe may be reading more. Wait, do not fail.
            status("waiting", "no profitable trade between markets with fresh prices and nothing to feed" + (onlyGood?.let { " for $it" } ?: "") + "; checking again in 5 minutes")
            clock.sleep(5.minutes)
            continue
        }
        status("plan", plan.summary())
        val key = "${plan.good}:${plan.source.symbol}:${plan.destination.symbol}"

        phase("travel to source", plan.source.symbol) {
            ensureFuel(Travel.fuelCost(plan.legToSource, FlightMode.CRUISE) + 10)
            travelTo(plan.source.symbol)
        }

        val bought = phase("buy", "${plan.good} at ${plan.source.symbol}") {
            dock(ship)
            val live = refreshMarket(plan.source.symbol)
            val rules = if (plan.feeds) assumptions.forFeeding() else assumptions
            val margin = Trading.stillPays(plan, live, rules)
            if (margin == null) {
                status(detail = "${plan.good} at ${plan.source.symbol} no longer pays; setting the pair aside")
                setAside[key] = now.plusSeconds(30.minutes.inWholeSeconds)
                0
            } else {
                buyLoad(plan, live, rules)
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
        // A wanted ship the bank can pay for is worth a detour: a hauler earns its price back in an hour on fresh routes,
        // and the bank sat at a million for an hour on 2026-09-06 because nobody happened to dock at the yard.
        val detour = affordableGoalYard()
        if (detour != null && (lastYardDetour == null || Duration.between(lastYardDetour, clock.now()).toMinutes() >= 20)) {
            lastYardDetour = clock.now()
            phase("expand", "detour to ${detour.symbol}") { travelTo(detour.symbol); dock(ship); maybeExpand() }
        }
    }
}

/** A yard in this system that sells a goal the bank can pay for now, nearest first; null when nothing is both wanted and affordable. */
private fun BehaviourScope.affordableGoalYard(): model.system.Waypoint? {
    val snap = snapshot()
    val credits = snap.agent?.credits ?: return null
    val fleet = snap.ships.values
    val wanted = shared.goals.fleet.filter { with(BehaviourScope.Companion) { it.buyableAt(me.nav.systemSymbol, shared.plan) } && it.owned(fleet) < it.count }
    if (wanted.isEmpty()) return null
    val yards = snap.waypointsIn(me.nav.systemSymbol).filter { it.hasShipyard }
    return Tour.nearest(here, yards.filter { y ->
        val yard = snap.shipyards[y.symbol] ?: return@filter false
        wanted.any { g -> yard.priceOf(g.type)?.let { price -> credits - price >= g.reserve } == true }
    })
}

/** Buys one trade volume at a time while the live price still leaves the margin. Returns units bought. */
internal suspend fun BehaviourScope.buyLoad(plan: TradePlan, market: Market, assumptions: TradingAssumptions): Int {
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


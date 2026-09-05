package behaviour

import behaviour.decisions.Intentions
import behaviour.decisions.Selling
import engine.VerbFailure
import knowledge.MarketHealth
import plan.Leg
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A worker bee: runs its chain's legs in turn, buying at each leg's source and selling at its
 * destination whether or not the leg pays on its own. The chain's ledger, not the leg, decides
 * whether that was worth it; every transaction the bee makes is tagged with the chain.
 */
val feedSpec = BehaviourSpec(
    name = "feed",
    description = "Work a chain: haul each leg's good from its source to its destination, in turn, regardless of the leg's own margin.",
    params = listOf(ParamSpec("chain", "The chain to work, by id", required = true)),
    validate = { snapshot, ship, params ->
        buildList {
            if (ship.cargo.capacity == 0) add("${ship.symbol} has no cargo hold")
            val chain = snapshot.plan?.chains?.firstOrNull { it.id == params["chain"] }
            if (chain == null) add("no chain '${params["chain"]}' in the plan")
            else chain.legs.forEach { leg ->
                if (snapshot.waypoints[leg.from] == null) add("unknown waypoint ${leg.from}")
                if (snapshot.waypoints[leg.to] == null) add("unknown waypoint ${leg.to}")
            }
        }
    },
    run = { feed() },
)

suspend fun BehaviourScope.feed() {
    val chainId = param("chain") ?: throw BehaviourFailure("feed needs --chain")
    setChain(ship, chainId)
    try {
        if (!me.cargo.isEmpty) phase("sell leftovers") { sellLeftovers() }
        var turn = 0
        while (true) {
            clock.sleep(1.seconds)
            val chain = shared.plan.chain(chainId) ?: throw BehaviourFailure("chain $chainId is gone from the plan")
            if (chain.legs.isEmpty()) { status("waiting", "chain $chainId has no legs"); clock.sleep(5.minutes); continue }
            // Spread the team over the legs: each ship starts its rotation at a different leg.
            val leg = chain.legs[(turn + chain.ships.indexOf(ship).coerceAtLeast(0)) % chain.legs.size]
            turn++
            runLeg(leg, chain.reserve)
        }
    } finally {
        setChain(ship, null)
    }
}

private suspend fun BehaviourScope.runLeg(leg: Leg, reserve: Long) {
    // A consumer already stocked to the brim pays nothing and grows nothing: let it drain first.
    snapshot().markets[leg.to]?.good(leg.good)?.let { bid ->
        if (MarketHealth.saturated(bid)) {
            status("waiting", "${leg.to} is saturated with ${leg.good} (${MarketHealth.describe(bid)}); letting it drain for 10 minutes")
            clock.sleep(10.minutes)
            return
        }
    }
    phase("travel to source", "${leg.good} at ${leg.from}") { travelTo(leg.from) }
    val bought = phase("buy", "${leg.good} at ${leg.from}") {
        dock(ship)
        val market = refreshMarket(leg.from)
        val offer = market.good(leg.good)
        if (offer == null) {
            status(detail = "${leg.from} does not sell ${leg.good} now; skipping the leg")
            0
        } else if (MarketHealth.starved(offer)) {
            // Taking the last of a producer's stock, or buying while its own inputs are unmet, only drives its price up.
            status(detail = "${leg.from} is starved of ${leg.good} (${MarketHealth.describe(offer)}); not buying")
            0
        } else {
            val spendable = agent().credits - reserve
            val want = minOf(me.cargoSpaceLeft, (spendable / offer.purchasePrice).toInt())
            if (want <= 0) {
                status(detail = "bank ${Intentions.format(agent().credits)} is at the chain's reserve of ${Intentions.format(reserve)}; not buying")
                0
            } else purchase(ship, leg.good, want).units
        }
    }
    if (bought == 0) { clock.sleep(2.minutes); return }
    phase("travel to destination", "${leg.good} to ${leg.to}") { travelTo(leg.to) }
    phase("deliver", "${bought} ${leg.good} at ${leg.to}") {
        dock(ship)
        val market = refreshMarket(leg.to)
        val (toSell, toDrop) = Selling.split(me.cargo, market)
        toSell.forEach { line -> sell(ship, line.symbol, line.units).also { status(detail = "sold ${it.units} ${line.symbol} for ${Intentions.format(it.credits)}") } }
        toDrop.forEach { line -> jettison(ship, line.symbol, line.units) }
        try { refuel(ship) } catch (e: VerbFailure) { status(detail = "could not refuel: ${e.message}") }
        refreshMarket(leg.to)
    }
}

private suspend fun BehaviourScope.sellLeftovers() {
    val market = Selling.bestMarketFor(me.cargo, here, snapshot()) ?: run { me.cargo.inventory.forEach { jettison(ship, it.symbol, it.units) }; return }
    travelTo(market.symbol)
    dock(ship)
    val live = refreshMarket(market.symbol)
    val (toSell, toDrop) = Selling.split(me.cargo, live)
    toSell.forEach { sell(ship, it.symbol, it.units) }
    toDrop.forEach { jettison(ship, it.symbol, it.units) }
}

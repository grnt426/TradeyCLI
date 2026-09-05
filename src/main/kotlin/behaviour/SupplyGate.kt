package behaviour

import behaviour.decisions.Intentions
import behaviour.decisions.Selling
import engine.VerbFailure
import knowledge.MarketAssumptions
import knowledge.MarketHealth
import model.Construction
import model.market.Market
import model.market.MarketTradeGood
import model.market.TradeSymbol
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Feed a construction site: buy the material it is shortest of at the cheapest market that
 * sells it, haul it there (through a fuel stop when the tank cannot cover the leg), and supply
 * it. Every purchase is tagged with the site so the ledger shows what the gate really costs as
 * the prices climb.
 *
 * The producer is kept healthy on the way: per visit it gives up only what
 * [MarketHealth.healthyUnits] allows for its stock, and when that is nothing (stock LIMITED or
 * below, or production RESTRICTED because its own imports are unmet) the ship nurses it instead,
 * hauling the producer's most starved input to it with the budget above the reserve. That is what
 * brings the part's price down; buying through the wall only raises it (docs/market-mechanics.md).
 */
val supplyGateSpec = BehaviourSpec(
    name = "supplyGate",
    description = "Buy what a construction site still needs and deliver it, load after load, until it is complete or the reserve is hit.",
    params = listOf(
        ParamSpec("site", "The waypoint under construction", required = true),
        ParamSpec("reserve", "Credits the bank never drops below (default 300000)"),
        ParamSpec("only", "Restrict to one material, such as FAB_MATS"),
        ParamSpec("nurse", "off to buy regardless of the producer's health (default on)"),
    ),
    validate = { snapshot, ship, params ->
        buildList {
            if (ship.cargo.capacity == 0) add("${ship.symbol} has no cargo hold")
            val site = snapshot.waypoints[params["site"]]
            if (site == null) add("unknown waypoint ${params["site"]}") else if (!site.isUnderConstruction) add("${site.symbol} is not under construction")
        }
    },
    run = { supplyGate() },
)

suspend fun BehaviourScope.supplyGate() {
    val site = param("site")!!.uppercase()
    val reserve = param("reserve")?.toLongOrNull() ?: 300_000L
    val only = param("only")?.uppercase()?.let { TradeSymbol.valueOf(it) }
    val nurse = param("nurse") != "off"
    val rules = MarketAssumptions()
    setChain(ship, "gate:$site")
    try {
        while (true) {
            clock.sleep(1.seconds)
            val construction = phase("check site") { construction(site) }
            if (construction.isComplete) { status("done", "$site is complete"); return }
            // Carry what is in the hold first, then the material with the largest share still missing.
            val carried = me.cargo.inventory.firstOrNull { line -> construction.remaining(line.symbol) > 0 }
            val material = carried?.symbol ?: construction.outstanding
                .filter { only == null || it.tradeSymbol == only }
                .maxByOrNull { (it.required - it.fulfilled).toDouble() / it.required }?.tradeSymbol
                ?: run { status("done", "nothing left that this ship may supply"); return }
            if (carried == null) {
                val (market, price) = cheapestSource(material) ?: throw BehaviourFailure("nothing in ${me.nav.systemSymbol} sells $material")
                val spendable = agent().credits - reserve
                if (spendable <= price) {
                    status("waiting", "bank ${Intentions.format(agent().credits)} is at the reserve of ${Intentions.format(reserve)}; checking again in 10 minutes")
                    clock.sleep(10.minutes)
                    continue
                }
                val listing = snapshot().markets[market]?.good(material)
                val healthy = if (nurse && listing != null) MarketHealth.healthyUnits(listing, rules) else Int.MAX_VALUE
                val want = minOf(me.cargoSpaceLeft, construction.remaining(material).toInt(), (spendable / price).toInt(), healthy)
                if (want <= 0) {
                    // The producer cannot spare any: bring it what it is short of instead.
                    val producer = snapshot().markets[market]!!
                    val leg = nurseLeg(producer, material, spendable, rules)
                    if (leg == null) {
                        status("waiting", "$material at $market is ${MarketHealth.explain(listing!!, rules)}; nothing to feed it with; checking again in 10 minutes")
                        clock.sleep(10.minutes)
                        continue
                    }
                    nurse(producer, material, leg)
                    continue
                }
                phase("buy", "$want $material at $market") {
                    travelVia(market)
                    dock(ship)
                    val live = refreshMarket(market).good(material)
                    val allowed = if (nurse && live != null) minOf(want, MarketHealth.healthyUnits(live, rules)) else want
                    if (allowed <= 0) {
                        status(detail = "$material at $market is ${live?.let { MarketHealth.explain(it, rules) }}; not buying this visit")
                    } else {
                        val bought = purchase(ship, material, allowed)
                        status(detail = "bought ${bought.units} $material for ${Intentions.format(bought.credits)} (${bought.averagePrice.toInt()} each); producer read ${live?.let { MarketHealth.describe(it) }}")
                    }
                }
                if (me.unitsOf(material) == 0) continue
            }
            phase("haul", "${me.unitsOf(material)} $material to $site") { travelVia(site) }
            phase("supply", "$material at $site") {
                dock(ship)
                val units = me.unitsOf(material)
                val after = supplyConstruction(site, ship, material, units)
                val left = after.remaining(material)
                status(detail = "delivered $units $material; $left still needed" + if (after.isComplete) "; COMPLETE" else "")
                try { refuel(ship) } catch (e: VerbFailure) { status(detail = "could not refuel at $site: ${e.message}") }
            }
        }
    } finally {
        setChain(ship, null)
    }
}

private fun BehaviourScope.cheapestSource(good: TradeSymbol): Pair<String, Int>? =
    snapshot().marketsIn(me.nav.systemSymbol)
        .mapNotNull { m -> m.good(good)?.purchasePrice?.let { m.symbol to it } }
        .minByOrNull { it.second }

/** An input the producer is short of, where to buy it, and how many units. */
data class NurseLeg(val input: MarketTradeGood, val source: Market, val offer: MarketTradeGood, val units: Int)

/**
 * The most starved input of [material] at [producer] that some healthy market in the system sells
 * for no less than [MarketAssumptions.nurseMinSellRatio] of what the producer pays back; null when
 * nothing can be fed.
 */
fun BehaviourScope.nurseLeg(producer: Market, material: TradeSymbol, spendable: Long, rules: MarketAssumptions): NurseLeg? {
    val system = me.nav.systemSymbol
    for (input in MarketHealth.starvedInputs(producer, material)) {
        if (MarketHealth.saturated(input, rules)) continue
        val candidates = snapshot().marketsIn(system)
            .filter { it.symbol != producer.symbol }
            .mapNotNull { m -> m.good(input.symbol)?.let { offer -> m to offer } }
            .filter { (_, offer) -> !MarketHealth.starved(offer, rules) && offer.purchasePrice > 0 && input.sellPrice >= offer.purchasePrice * rules.nurseMinSellRatio }
        val (source, offer) = candidates.minByOrNull { (_, offer) -> offer.purchasePrice } ?: continue
        val units = minOf(me.cargoSpaceLeft, (spendable / offer.purchasePrice).toInt(), (rules.nurseVolumesPerVisit * input.tradeVolume).toInt())
        if (units > 0) return NurseLeg(input, source, offer, units)
    }
    return null
}

/** Buy the input, haul it to the producer, sell it there, and note how the producer looks after. */
private suspend fun BehaviourScope.nurse(producer: Market, material: TradeSymbol, leg: NurseLeg) {
    val good = leg.input.symbol
    phase("nurse: buy", "${leg.units} $good at ${leg.source.symbol} for ${producer.symbol}") {
        travelVia(leg.source.symbol)
        dock(ship)
        val live = refreshMarket(leg.source.symbol).good(good)
        if (live == null || MarketHealth.starved(live)) { status(detail = "${leg.source.symbol} no longer spares $good"); return@phase }
        val bought = purchase(ship, good, minOf(leg.units, (agent().credits / live.purchasePrice).toInt().coerceAtLeast(0)))
        status(detail = "bought ${bought.units} $good for ${Intentions.format(bought.credits)} to feed $material production at ${producer.symbol}")
    }
    if (me.unitsOf(good) == 0) { clock.sleep(2.minutes); return }
    phase("nurse: feed", "${me.unitsOf(good)} $good to ${producer.symbol}") {
        travelVia(producer.symbol)
        dock(ship)
        val live = refreshMarket(producer.symbol)
        val (toSell, toDrop) = Selling.split(me.cargo, live)
        toSell.forEach { line -> sell(ship, line.symbol, line.units).also { status(detail = "fed ${it.units} ${line.symbol} for ${Intentions.format(it.credits)}") } }
        toDrop.forEach { line -> jettison(ship, line.symbol, line.units) }
        val after = refreshMarket(producer.symbol)
        status(detail = "${producer.symbol} now: $good ${after.good(good)?.let { MarketHealth.describe(it) }}, $material ${after.good(material)?.let { MarketHealth.describe(it) }} at ${after.good(material)?.purchasePrice}")
        try { refuel(ship) } catch (e: VerbFailure) { status(detail = "could not refuel: ${e.message}") }
    }
}

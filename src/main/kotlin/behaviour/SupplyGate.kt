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
        ParamSpec("reserveShare", "Keep at least this share of the bank, 0..1, whatever the fixed reserve says (a far gate's network share)"),
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
    val fixedReserve = param("reserve")?.toLongOrNull() ?: 300_000L
    val reserveShare = param("reserveShare")?.toDoubleOrNull()
    // A share-based reserve moves with the bank: the network never draws below that share of what the empire has.
    suspend fun currentReserve(): Long = maxOf(fixedReserve, reserveShare?.let { (agent().credits * it).toLong() } ?: 0L)
    val only = param("only")?.uppercase()?.let { TradeSymbol.valueOf(it) }
    val nurse = param("nurse") != "off"
    val rules = knowledge.Strategy.market(shared.plan.phase)
    setChain(ship, "gate:$site")
    try {
        while (true) {
            clock.sleep(1.seconds)
            val construction = phase("check site") { construction(site) }
            shared.constructionBill = construction.materials
            if (shared.plan.phase == plan.Phase.ESCAPE && !construction.isComplete) {
                val remaining = knowledge.Strategy.remainingCost(construction.materials, snapshot())
                if (remaining != null && !shared.plan.rushing && knowledge.Strategy.gateRush(agent().credits, remaining)) {
                    status(detail = "finishing costs about ${Intentions.format(remaining)} against ${Intentions.format(agent().credits)}: the rush is on, traders come to the gate")
                    val snap = snapshot()
                    shared.editPlan("gate rush") { p ->
                        // Every trading hauler up to the rush size joins the gate; the goal makes sure there are that many.
                        var next = p.copy(rushing = true).withGoal(plan.FleetGoal(model.ship.ShipType.SHIP_LIGHT_HAULER, maxOf(knowledge.Strategy.RUSH_HAULERS, p.goals.fleet.firstOrNull { it.type == model.ship.ShipType.SHIP_LIGHT_HAULER }?.count ?: 0), reserve = knowledge.Strategy.POST_GATE_RESERVE))
                        var onGate = next.assignments.count { it.behaviour == "supplyGate" }
                        next.assignments.filter { it.behaviour == "trade" }.forEach { a ->
                            val hauler = snap.ships[a.ship]
                            if (onGate < knowledge.Strategy.RUSH_HAULERS && hauler != null && hauler.cargo.capacity >= 60) {
                                next = next.with(plan.Assignment(a.ship, "supplyGate", mapOf("site" to site, "reserve" to knowledge.Strategy.POST_GATE_RESERVE.toString())))
                                onGate++
                            }
                        }
                        next
                    }
                }
            }
            if (construction.isComplete) {
                status("done", "$site is complete")
                if (shared.plan.phase == plan.Phase.ESCAPE) shared.advancePhase(plan.Phase.BOOM)
                return
            }
            // A hold inherited from an earlier job (a killed trade run's clothing), or a material the site
            // has since finished with, is sold before anything else.
            val materials = construction.outstanding.map { it.tradeSymbol }.toSet()
            if (me.cargo.inventory.any { it.symbol !in materials }) phase("sell leftovers") { sellLeftovers(keep = materials) }
            // Carry what is in the hold first. Otherwise take the material whose producer can spare the most
            // now, by the share still missing: three haulers on one short producer is what the rate budget prevents.
            val carried = me.cargo.inventory.firstOrNull { line -> construction.remaining(line.symbol) > 0 }
            var material = carried?.symbol
            if (carried == null) {
                val reserve = currentReserve()
                val spendable = agent().credits - reserve
                val wanted = construction.outstanding
                    .filter { only == null || it.tradeSymbol == only }
                    .sortedByDescending { (it.required - it.fulfilled).toDouble() / it.required }
                if (wanted.isEmpty()) { status("done", "nothing left that this ship may supply"); return }
                if (spendable <= 0) {
                    status("waiting", "bank ${Intentions.format(agent().credits)} is at the reserve of ${Intentions.format(reserve)}; checking again in 10 minutes")
                    clock.sleep(10.minutes)
                    continue
                }
                data class Pick(val material: TradeSymbol, val market: String, val listing: MarketTradeGood?, val units: Int, val worthwhile: Boolean)
                val picks = wanted.mapNotNull { m ->
                    val (market, price) = cheapestSource(m.tradeSymbol) ?: return@mapNotNull null
                    val listing = snapshot().markets[market]?.good(m.tradeSymbol)
                    val healthy = if (nurse && listing != null) minOf(MarketHealth.healthyUnits(listing, rules), shared.takeBudget.available(market, listing, rules, clock.now())) else Int.MAX_VALUE
                    // What the site still needs beyond what the other haulers already carry or are buying for it.
                    val remaining = (construction.remaining(m.tradeSymbol).toInt() - shared.reservedByOthers(ship, site, m.tradeSymbol.name)).coerceAtLeast(0)
                    val units = minOf(me.cargoSpaceLeft, remaining, (spendable / price).toInt(), healthy)
                    // Six units on an 80 hold is a wasted round trip: a load must be a share of the hold, a volume, or the last of the bill.
                    val floor = minOf(remaining, maxOf((me.cargo.capacity * rules.minHaulShare).toInt(), listing?.tradeVolume ?: 1))
                    Pick(m.tradeSymbol, market, listing, units, units >= floor)
                }
                if (picks.isEmpty()) {
                    // No market shows a price for any of it right now. A producer we know of (it lists the
                    // good as an export) may just be unread: go and read it rather than fail.
                    val producer = wanted.firstNotNullOfOrNull { m -> snapshot().marketsIn(me.nav.systemSymbol).firstOrNull { it.typeOf(m.tradeSymbol) == model.market.TradeGoodType.EXPORT }?.let { it to m.tradeSymbol } }
                        ?: throw BehaviourFailure("nothing in ${me.nav.systemSymbol} exports what $site needs")
                    phase("read producer", "${producer.second} at ${producer.first.symbol}") {
                        travelVia(producer.first.symbol)
                        dock(ship)
                        val live = refreshMarket(producer.first.symbol).good(producer.second)
                        status(detail = "${producer.second} at ${producer.first.symbol} reads ${live?.let { MarketHealth.describe(it) } ?: "no prices"}")
                    }
                    clock.sleep(30.seconds)
                    continue
                }
                var pick = picks.firstOrNull { it.units > 0 && it.worthwhile }
                if (pick != null && nurse && rules.protectStarvedChains) {
                    // A producer with a short input is fed, not drawn on, until the input is back to MODERATE; taking from it only raises the bill.
                    val producer = snapshot().markets[pick.market]
                    val growing = rules.takeWhenGrowing && pick.listing?.activity.let { it == model.market.ActivityLevel.GROWING || it == model.market.ActivityLevel.STRONG }
                    val starved = producer != null && !growing && MarketHealth.starvedInputs(producer, pick.material).any { it.supply <= model.market.SupplyLevel.LIMITED }
                    if (starved) {
                        val leg = nurseLeg(producer!!, pick.material, spendable, rules)
                        if (leg != null) { status(detail = "${pick.material}'s inputs at ${pick.market} are short; feeding before taking"); nurse(producer, leg); continue }
                        status(detail = "${pick.material}'s inputs at ${pick.market} are short and nothing can feed them; taking anyway")
                    }
                }
                if (pick == null) {
                    // A stale reading is not a reason to park: go and read the producer before deciding it has nothing.
                    val stale = picks.firstOrNull { p ->
                        val read = snapshot().markets[p.market]?.lastRead
                        read == null || java.time.Duration.between(read, clock.now()).toMinutes() >= rules.producerReadStaleMinutes
                    }
                    if (stale != null) {
                        phase("read producer", "${stale.material} at ${stale.market}") {
                            travelVia(stale.market)
                            dock(ship)
                            val live = refreshMarket(stale.market).good(stale.material)
                            status(detail = "${stale.material} at ${stale.market} reads ${live?.let { MarketHealth.describe(it) } ?: "unlisted"}")
                        }
                        continue
                    }
                    // Every producer is short or its rate is spent: bring the shortest one what it lacks, two levels deep.
                    val leg = picks.firstNotNullOfOrNull { p -> snapshot().markets[p.market]?.let { producer -> nurseLeg(producer, p.material, spendable, rules)?.let { producer to it } } }
                    if (leg != null) { nurse(leg.first, leg.second); continue }
                    // Nothing to haul and nothing to feed: earn a load meanwhile rather than park; the rate refills while we are away.
                    val why = picks.joinToString("; ") { p -> "${p.material} at ${p.market} is ${p.listing?.let { MarketHealth.explain(it, rules) } ?: "unread"}" + (if (p.units in 1 until (me.cargo.capacity * rules.minHaulShare).toInt()) " (only ${p.units} this hour)" else "") }
                    if (me.cargo.capacity > 0 && tradeOnce(knowledge.Strategy.trading(shared.plan.phase), "gate:$site")) continue
                    status("waiting", "$why; nothing to feed and no trade pays; checking again in 10 minutes")
                    clock.sleep(10.minutes)
                    continue
                }
                material = pick.material
                shared.reserveDelivery(ship, site, material.name, pick.units)
                phase("buy", "${pick.units} $material at ${pick.market}") {
                    travelVia(pick.market)
                    dock(ship)
                    val live = refreshMarket(pick.market).good(material)
                    // Another hauler may have finished this material while we flew: re-read the bill before paying.
                    val stillNeeded = (construction(site).remaining(material).toInt() - shared.reservedByOthers(ship, site, material.name)).coerceAtLeast(0)
                    if (stillNeeded <= 0) { status(detail = "$material is covered by the other haulers; not buying"); shared.releaseDelivery(ship); return@phase }
                    val allowed = if (nurse && live != null) {
                        val cap = minOf(pick.units, stillNeeded, MarketHealth.healthyUnits(live, rules))
                        shared.takeBudget.take(pick.market, live, cap, rules, clock.now())
                    } else minOf(pick.units, stillNeeded)
                    if (allowed <= 0) {
                        status(detail = "$material at ${pick.market} is ${live?.let { MarketHealth.explain(it, rules) }}; the rate is spent, not buying this visit")
                    } else {
                        val bought = purchase(ship, material, allowed)
                        shared.reserveDelivery(ship, site, material.name, me.unitsOf(material))
                        if (live != null && bought.units < allowed) shared.takeBudget.refund(pick.market, live, allowed - bought.units, rules, clock.now())
                        status(detail = "bought ${bought.units} $material for ${Intentions.format(bought.credits)} (${bought.averagePrice.toInt()} each); producer read ${live?.let { MarketHealth.describe(it) }}, ${shared.takeBudget.available(pick.market, live ?: return@phase, rules, clock.now())} more this hour")
                    }
                }
                if (me.unitsOf(material) == 0) { shared.releaseDelivery(ship); continue }
            }
            val chosen: TradeSymbol = material ?: continue
            phase("haul", "${me.unitsOf(chosen)} $chosen to $site") { travelVia(site) }
            phase("supply", "$chosen at $site") {
                dock(ship)
                val units = me.unitsOf(chosen)
                val after = supplyConstruction(site, ship, chosen, units)
                shared.releaseDelivery(ship)
                val left = after.remaining(chosen)
                status(detail = "delivered $units $chosen; $left still needed" + if (after.isComplete) "; COMPLETE" else "")
                try { refuel(ship) } catch (e: VerbFailure) { status(detail = "could not refuel at $site: ${e.message}") }
            }
        }
    } finally {
        shared.releaseDelivery(ship)
        setChain(ship, null)
    }
}

private fun BehaviourScope.cheapestSource(good: TradeSymbol): Pair<String, Int>? =
    snapshot().marketsIn(me.nav.systemSymbol)
        .mapNotNull { m -> m.good(good)?.purchasePrice?.let { m.symbol to it } }
        .minByOrNull { it.second }

/** An input a producer is short of, the producer to bring it to, where to buy it, and how many units. */
data class NurseLeg(val target: Market, val material: TradeSymbol, val input: MarketTradeGood, val source: Market, val offer: MarketTradeGood, val units: Int)

/**
 * The most starved input of [material] at [producer] that some healthy market in the system sells
 * for no less than [MarketAssumptions.nurseMinSellRatio] of what the producer pays back. When no
 * market can spare an input, the input's own producer is nursed instead (iron short everywhere
 * means the refinery wants ore), down to [depth] levels. Null when nothing can be fed.
 */
fun BehaviourScope.nurseLeg(producer: Market, material: TradeSymbol, spendable: Long, rules: MarketAssumptions, depth: Int = 2): NurseLeg? {
    val system = me.nav.systemSymbol
    for (input in MarketHealth.starvedInputs(producer, material)) {
        if (MarketHealth.saturated(input, rules) || input.supply >= rules.feedUntil) continue
        val candidates = snapshot().marketsIn(system)
            .filter { it.symbol != producer.symbol }
            .mapNotNull { m -> m.good(input.symbol)?.let { offer -> m to offer } }
            .filter { (_, offer) -> !MarketHealth.starved(offer, rules) && offer.purchasePrice > 0 && input.sellPrice >= offer.purchasePrice * rules.nurseMinSellRatio }
        val best = candidates.minByOrNull { (_, offer) -> offer.purchasePrice }
        if (best != null) {
            val (source, offer) = best
            val units = minOf(me.cargoSpaceLeft, (spendable / offer.purchasePrice).toInt(), (rules.nurseVolumesPerVisit * input.tradeVolume).toInt())
            if (units > 0) return NurseLeg(producer, material, input, source, offer, units)
            continue
        }
        if (depth > 1) {
            val upstream = snapshot().marketsIn(system).firstOrNull { m -> m.symbol != producer.symbol && m.typeOf(input.symbol) == model.market.TradeGoodType.EXPORT && m.hasPrices }
            if (upstream != null) nurseLeg(upstream, input.symbol, spendable, rules, depth - 1)?.let { return it }
        }
    }
    return null
}

/** Buy the input, haul it to the leg's target producer, sell it there, and note how the producer looks after. */
private suspend fun BehaviourScope.nurse(gateProducer: Market, leg: NurseLeg) {
    val good = leg.input.symbol
    val site = shared.plan.assignmentFor(ship)?.params?.get("site")?.uppercase() ?: gateProducer.symbol
    setChain(ship, "nurse:$site")
    try { nurseRun(leg.target, leg.material, leg, good) } finally { setChain(ship, "gate:$site") }
}

private suspend fun BehaviourScope.nurseRun(producer: Market, material: TradeSymbol, leg: NurseLeg, good: TradeSymbol) {
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

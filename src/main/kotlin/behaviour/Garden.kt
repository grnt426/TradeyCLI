package behaviour

import behaviour.decisions.Intentions
import behaviour.decisions.Selling
import engine.VerbFailure
import knowledge.MarketAssumptions
import knowledge.MarketHealth
import model.market.Market
import model.market.MarketTradeGood
import model.market.TradeGoodType

/** The most starved importer in the system that a healthy market can supply, and how many units to bring. */
data class GardenLeg(val target: Market, val input: MarketTradeGood, val source: Market, val offer: MarketTradeGood, val units: Int, val marginPerUnit: Int)

/**
 * What to feed when no route pays: importers read SCARCE or LIMITED, from the cheapest healthy
 * source, preferring legs that still pay something and never selling for less than
 * [MarketAssumptions.nurseMinSellRatio] of the cost. The official rule is that supplying an
 * import grows its consumption, and at home every listing we fed doubled its trade volume; this
 * spends idle ship time on that growth.
 */
fun BehaviourScope.gardenLeg(spendable: Long, rules: MarketAssumptions): GardenLeg? {
    val system = me.nav.systemSymbol
    val markets = snapshot().pricedMarketsIn(system)
    val candidates = markets.flatMap { target ->
        target.tradeGoods.filter { it.type == TradeGoodType.IMPORT && it.supply <= model.market.SupplyLevel.LIMITED }.mapNotNull { input ->
            val best = markets.filter { it.symbol != target.symbol }
                .mapNotNull { m -> m.good(input.symbol)?.let { offer -> m to offer } }
                .filter { (_, offer) -> offer.type != TradeGoodType.IMPORT && !MarketHealth.starved(offer, rules) && offer.purchasePrice > 0 && input.sellPrice >= offer.purchasePrice * rules.nurseMinSellRatio }
                .minByOrNull { (_, offer) -> offer.purchasePrice } ?: return@mapNotNull null
            val (source, offer) = best
            val units = minOf(me.cargoSpaceLeft, (spendable / offer.purchasePrice).toInt(), (rules.nurseVolumesPerVisit * input.tradeVolume).toInt())
            if (units <= 0) null else GardenLeg(target, input, source, offer, units, input.sellPrice - offer.purchasePrice)
        }
    }
    // The gate's chains first (the producers of what the site needs and of their inputs), then the hungriest importer,
    // then the leg that loses least.
    val gateMarkets = knowledge.Strategy.watchMarkets(snapshot()).toSet()
    return candidates.sortedWith(compareBy({ if (it.target.symbol in gateMarkets) 0 else 1 }, { it.input.supply.ordinal }, { -it.marginPerUnit })).firstOrNull()
}

/** One gardening trip: buy the input, sell it to the starved importer, refuel. Tagged as market health. Returns false when there is nothing to feed. */
suspend fun BehaviourScope.gardenOnce(reserve: Long, rules: MarketAssumptions, restoreTag: String?): Boolean {
    val spendable = agent().credits - reserve
    if (spendable <= 0) return false
    val leg = gardenLeg(spendable, rules) ?: return false
    val good = leg.input.symbol
    setChain(ship, "nurse:${me.nav.systemSymbol}")
    try {
        status("garden", "${leg.units} $good ${leg.source.symbol} -> ${leg.target.symbol} (${MarketHealth.describe(leg.input)}, ${leg.marginPerUnit}/unit)")
        phase("garden: buy", "$good at ${leg.source.symbol}") {
            travelVia(leg.source.symbol)
            dock(ship)
            val live = refreshMarket(leg.source.symbol).good(good)
            if (live == null || MarketHealth.starved(live, rules)) { status(detail = "${leg.source.symbol} no longer spares $good"); return@phase }
            val bought = purchase(ship, good, minOf(leg.units, (agent().credits / live.purchasePrice).toInt().coerceAtLeast(0)))
            status(detail = "bought ${bought.units} $good for ${Intentions.format(bought.credits)} to feed ${leg.target.symbol}")
        }
        if (me.unitsOf(good) == 0) return false
        phase("garden: feed", "${me.unitsOf(good)} $good to ${leg.target.symbol}") {
            travelVia(leg.target.symbol)
            dock(ship)
            val live = refreshMarket(leg.target.symbol)
            val (toSell, toDrop) = Selling.split(me.cargo, live)
            toSell.forEach { line -> sell(ship, line.symbol, line.units).also { status(detail = "fed ${it.units} ${line.symbol} for ${Intentions.format(it.credits)}") } }
            toDrop.forEach { line -> jettison(ship, line.symbol, line.units) }
            val after = refreshMarket(leg.target.symbol).good(good)
            status(detail = "${leg.target.symbol} now reads $good ${after?.let { MarketHealth.describe(it) }}")
            try { refuel(ship) } catch (e: VerbFailure) { status(detail = "could not refuel: ${e.message}") }
        }
        return true
    } finally {
        setChain(ship, restoreTag)
    }
}

package behaviour

import behaviour.decisions.Intentions
import engine.VerbFailure
import model.Construction
import model.market.TradeSymbol
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Feed a construction site: buy the material it is shortest of at the cheapest market that
 * sells it, haul it there (through a fuel stop when the tank cannot cover the leg), and supply
 * it. Every purchase is tagged with the site so the ledger shows what the gate really costs as
 * the prices climb.
 */
val supplyGateSpec = BehaviourSpec(
    name = "supplyGate",
    description = "Buy what a construction site still needs and deliver it, load after load, until it is complete or the reserve is hit.",
    params = listOf(
        ParamSpec("site", "The waypoint under construction", required = true),
        ParamSpec("reserve", "Credits the bank never drops below (default 300000)"),
        ParamSpec("only", "Restrict to one material, such as FAB_MATS"),
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
                val want = minOf(me.cargoSpaceLeft, construction.remaining(material).toInt(), (spendable / price).toInt())
                if (want <= 0) {
                    status("waiting", "bank ${Intentions.format(agent().credits)} is at the reserve of ${Intentions.format(reserve)}; checking again in 10 minutes")
                    clock.sleep(10.minutes)
                    continue
                }
                phase("buy", "$want $material at $market") {
                    travelVia(market)
                    dock(ship)
                    refreshMarket(market)
                    val bought = purchase(ship, material, want)
                    status(detail = "bought ${bought.units} $material for ${Intentions.format(bought.credits)} (${bought.averagePrice.toInt()} each)")
                }
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

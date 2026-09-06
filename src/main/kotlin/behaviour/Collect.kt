package behaviour

import behaviour.decisions.Intentions
import behaviour.decisions.Selling
import engine.VerbFailure
import knowledge.MarketHealth
import model.market.Market
import model.ship.components.Cargo
import kotlin.time.Duration.Companion.minutes

/**
 * The collector: a hauler that visits the drones parked on rocks, takes their ore aboard, and
 * sells it where it is needed most, the starved importers first (the refinery in ESCAPE), so the
 * mining fleet is a health investment and not a drift-home exercise. Serves the drones with the
 * fullest holds first; rests when none has enough to be worth the trip.
 */
val collectSpec = BehaviourSpec(
    name = "collect",
    description = "Visit the drones parked on rocks, take their ore, sell it to the starved importers; rest when none is worth a trip.",
    params = listOf(
        ParamSpec("drones", "Comma-separated drones to serve; default: every ship on mineInPlace"),
        ParamSpec("minShare", "A drone is worth a visit once its hold is this full, 0..1 (default 0.6)"),
    ),
    validate = { _, ship, _ -> buildList { if (ship.cargo.capacity < 30) add("${ship.symbol} has too small a hold to collect") } },
    run = { collect() },
)

suspend fun BehaviourScope.collect() {
    val named = param("drones")?.split(',')?.map { it.trim().uppercase() }?.toSet()
    val minShare = param("minShare")?.toDoubleOrNull() ?: 0.6
    if (!me.cargo.isEmpty) phase("sell leftovers") { sellLeftovers() }
    while (true) {
        clock.sleep(1.minutes.div(6))
        val snap = snapshot()
        val drones = (named ?: shared.plan.assignments.filter { it.behaviour == "mineInPlace" }.map { it.ship }.toSet())
            .mapNotNull { snap.ships[it] }
            .filter { it.cargo.capacity > 0 && shared.parked[it.symbol] != null }
        if (drones.isEmpty()) { status("waiting", "no drone is parked on a rock; checking again in 5 minutes"); clock.sleep(5.minutes); continue }
        // Fullest first, and only when the trip is worth it or the hauler is already nearly full of earlier pickups.
        val ready = drones.filter { it.cargo.units >= (it.cargo.capacity * minShare).toInt() }.sortedByDescending { it.cargo.units }
        if (ready.isEmpty() && me.cargo.units < me.cargo.capacity / 2) {
            status("waiting", "${drones.size} drone(s) parked, fullest at ${drones.maxOf { it.cargo.units }}/${drones.first().cargo.capacity}; checking again in 5 minutes")
            clock.sleep(5.minutes)
            continue
        }
        for (drone in ready) {
            if (me.cargoSpaceLeft < drone.cargo.capacity / 2) break
            val rock = shared.parked[drone.symbol] ?: continue
            phase("travel", "to ${drone.symbol} at $rock") { travelTo(rock) }
            phase("collect", "from ${drone.symbol}") {
                val live = verbs.ship(drone.symbol)
                for (line in live.cargo.inventory) {
                    val units = minOf(line.units, me.cargoSpaceLeft)
                    if (units <= 0) break
                    try {
                        transferCargo(drone.symbol, ship, line.symbol, units)
                        status(detail = "took $units ${line.symbol} from ${drone.symbol}; hold ${me.cargo.units}/${me.cargo.capacity}")
                    } catch (e: VerbFailure) {
                        status(detail = "could not take ${line.symbol} from ${drone.symbol}: ${e.message}")
                    }
                }
            }
        }
        if (me.cargo.isEmpty) continue
        val market = bestBuyerFor(me.cargo, snapshot()) ?: run { status("waiting", "nothing buys what the drones pulled"); clock.sleep(10.minutes); continue }
        phase("deliver", "${me.cargo.units} units to ${market.symbol}") {
            travelTo(market.symbol)
            dock(ship)
            val live = refreshMarket(market.symbol)
            val (toSell, toDrop) = Selling.split(me.cargo, live)
            var earned = 0L
            toSell.forEach { line -> earned += sell(ship, line.symbol, line.units).credits }
            toDrop.forEach { line -> jettison(ship, line.symbol, line.units) }
            val after = refreshMarket(market.symbol)
            status(detail = "sold for ${Intentions.format(earned)}; ${live.symbol} now " + toSell.joinToString(", ") { l -> "${l.symbol} ${after.good(l.symbol)?.let { MarketHealth.describe(it) }}" })
            try { refuel(ship) } catch (e: VerbFailure) { status(detail = "could not refuel: ${e.message}") }
        }
    }
}

/** The market that most needs what the hold carries: price weighted by the importer's hunger, distance ignored within a system. */
private fun BehaviourScope.bestBuyerFor(cargo: Cargo, snap: engine.Snapshot): Market? =
    snap.pricedMarketsIn(me.nav.systemSymbol).maxByOrNull { m ->
        cargo.inventory.sumOf { line -> m.good(line.symbol)?.let { g -> line.units * g.sellPrice * MarketHealth.feedWeight(g) } ?: 0.0 }
    }?.takeIf { m -> cargo.inventory.any { m.good(it.symbol) != null } }

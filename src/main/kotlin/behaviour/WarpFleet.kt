package behaviour

import behaviour.decisions.Selling
import behaviour.decisions.TradePlan
import behaviour.decisions.Trading
import behaviour.decisions.TradingAssumptions
import engine.Travel
import engine.VerbFailure
import knowledge.Strategy
import model.market.TradeSymbol
import model.ship.FlightMode
import model.ship.Ship
import model.ship.ShipNavStatus
import model.ship.ShipType
import model.system.WaypointType
import plan.Assignment
import plan.FleetGoal
import plan.Stage
import plan.SystemRecord
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The warp-only fleet (Grant, 2026-09-09): heavy freighters fitted with the explorers' warp drives,
 * trading in and out of the systems the gate network cannot reach. Behind the 142 gates we have
 * been refused at lie systems twice the size of a gated one (a median of 88 waypoints, four fuel
 * stations, twenty-odd planets and moons) that nobody has traded since the reset, and 3,930
 * systems have no gate at all. A first visit to an untouched gated system paid a heavy 0.95M in
 * its first hour against 0.42M for a return; these systems have had no first visit.
 *
 * Three behaviours. `donateWarpDrive`: an explorer takes its drive off at a shipyard and hands it
 * to its heavy. `refitWarp`: the heavy gives up one cargo hold III (three slots) for the drive (one
 * slot) and a cargo hold II when the market sells one, then becomes `warpTrade`. `warpTrade`: warp to
 * the best unheld gate-less or unbuilt-gate system in reach, read its markets, buy probes at its yard
 * to chart it and leave them there, trade until the routes are drained, fill the hold with the
 * export the rest of the galaxy pays most for, and warp on. Systems entered this way are held
 * warp-only; the gate fleet's rules leave them alone.
 */
const val WO = "WO"
private val WARP_DRIVE = TradeSymbol.MODULE_WARP_DRIVE_I
private val HOLD_III = TradeSymbol.MODULE_CARGO_HOLD_III
private val HOLD_II = TradeSymbol.MODULE_CARGO_HOLD_II

val donateWarpDriveSpec = BehaviourSpec(
    name = "donateWarpDrive",
    description = "Take the warp drive off at a shipyard, hand it to a heavy of the warp-only fleet, then trade.",
    params = listOf(
        ParamSpec("fleet", "The fleet this ship belongs to (WO)"),
        ParamSpec("to", "The heavy that gets the drive; set by the boom tick when it pairs the two"),
        ParamSpec("at", "The shipyard where the two meet; set with --to"),
    ),
    validate = { _, ship, _ -> buildList { if (!ship.canWarp && ship.cargo.inventory.none { it.symbol == WARP_DRIVE }) add("${ship.symbol} has no warp drive to give") } },
    run = { donateWarpDrive() },
)

val refitWarpSpec = BehaviourSpec(
    name = "refitWarp",
    description = "Meet an explorer at a shipyard, swap a cargo hold for its warp drive, then trade warp-only.",
    params = listOf(
        ParamSpec("fleet", "The fleet this ship belongs to (WO)"),
        ParamSpec("origin", "The yard system it was bought at, for the goal that bought it"),
        ParamSpec("from", "The explorer that hands over its drive; set by the boom tick"),
        ParamSpec("at", "The shipyard where the two meet; set with --from"),
    ),
    validate = { _, ship, _ -> buildList { if (ship.cargo.capacity < Strategy.HEAVY_HOLD) add("${ship.symbol} is not a heavy freighter") } },
    run = { refitWarp() },
)

val warpTradeSpec = BehaviourSpec(
    name = "warpTrade",
    description = "Warp to the nearest untouched gate-less or unbuilt-gate system, chart it with bought probes, drain its routes, warp on with a load.",
    params = listOf(ParamSpec("fleet", "The fleet this ship belongs to (WO)"), ParamSpec("origin", "The yard system it was bought at")),
    validate = { _, ship, _ -> buildList { if (!ship.canWarp) add("${ship.symbol} has no warp drive") } },
    run = { warpTrade() },
)

suspend fun BehaviourScope.donateWarpDrive() {
    while (true) {
        val to = param("to")
        val at = param("at")
        if (to == null || at == null) { status("waiting", "for a heavy of the warp-only fleet to pair with"); clock.sleep(2.minutes); continue }
        if (!reachYard(at)) { clock.sleep(10.minutes); continue }
        dock(ship)
        if (me.canWarp) phase("remove", "the warp drive at $at") { removeModule(ship, WARP_DRIVE) }
        val heavy = snapshot().ships[to]
        if (heavy == null) { status("waiting", "$to is gone; waiting for another pairing"); clock.sleep(5.minutes); continue }
        if (heavy.nav.waypointSymbol != at || heavy.nav.status == ShipNavStatus.IN_TRANSIT) { status("waiting", "at $at for $to"); clock.sleep(30.seconds); continue }
        phase("hand over", "the warp drive to $to") { transferCargo(ship, to, WARP_DRIVE, 1) }
        status("done", "warp drive handed to $to; trading from here")
        shared.editPlan("$ship gave its warp drive to $to") { p -> p.with(Assignment(ship, "trade")) }
        return
    }
}

suspend fun BehaviourScope.refitWarp() {
    val origin = param("origin")
    while (true) {
        if (me.canWarp) {
            status("done", "warp-capable; joining the warp-only fleet")
            shared.editPlan("$ship joins the warp-only fleet") { p -> p.with(Assignment(ship, "warpTrade", listOfNotNull("fleet" to WO, origin?.let { "origin" to it }).toMap())) }
            return
        }
        val from = param("from")
        val at = param("at")
        if (from == null || at == null) { status("waiting", "for an explorer of the warp-only fleet to pair with"); clock.sleep(2.minutes); continue }
        if (!reachYard(at)) { clock.sleep(10.minutes); continue }
        dock(ship)
        if (me.cargo.inventory.none { it.symbol == WARP_DRIVE }) { status("waiting", "at $at for $from to hand over its warp drive"); clock.sleep(30.seconds); continue }
        // A warp drive needs one slot; the frame ships full, and a cargo hold III gives back three.
        if (freeSlots(me) < 1) phase("remove", "one cargo hold III to make room") { removeModule(ship, HOLD_III) }
        phase("install", "the warp drive") { installModule(ship, WARP_DRIVE) }
        if (freeSlots(me) >= 2 && market(here.symbol)?.good(HOLD_II) != null) {
            phase("install", "a cargo hold II in the slots left") {
                try { purchase(ship, HOLD_II, 1); installModule(ship, HOLD_II) } catch (e: VerbFailure) { status(detail = "no cargo hold II fitted: ${e.message}") }
            }
        }
        // The hold that came out is cargo now: sold where something buys it, dropped otherwise.
        phase("sell leftovers", "the module that came out") { runCatching { sellLeftovers() }.onFailure { status(detail = "could not sell the old module: ${it.message}") } }
    }
}

suspend fun BehaviourScope.warpTrade() {
    var arrivedAt = clock.now()
    var current = me.nav.systemSymbol
    var dry = 0
    while (true) {
        clock.sleep(1.seconds)
        val system = me.nav.systemSymbol
        if (system != current) { current = system; arrivedAt = clock.now(); dry = 0 }
        if (snapshot().waypointsIn(system).isEmpty()) phase("map", system) { loadSystem(system) }
        if (shared.plan.system(system) == null) {
            shared.editPlan("the warp fleet entered $system") { p ->
                p.withSystem(SystemRecord(system, Stage.SETTLE, gate = null, gateBuilt = false, pioneer = ship, arrivedAt = clock.now().toString(), warpOnly = true, note = "warp only"))
            }
            phase("survey", system) { surveyMarkets(system) }
            kitProbes(system)
        }
        if (!me.cargo.isEmpty) phase("sell leftovers") { sellLeftovers() }
        val assumptions = Strategy.trading(shared.plan.phase)
        val plan = phase("plan") {
            Trading.rank(snapshot(), me, clock.now(), assumptions)
                .firstOrNull { !shared.routeTakenByOther("${it.good}@${it.source.symbol}", ship) && !shared.routeTakenByOther("${it.good}@${it.destination.symbol}", ship) }
                ?.also { shared.claimRoute(ship, "${it.good}@${it.source.symbol}", "${it.good}@${it.destination.symbol}") }
        }
        val dwell = Duration.between(arrivedAt, clock.now())
        if (plan != null && dwell.toHours() < Strategy.WO_MAX_HOURS) { dry = 0; tradeOnce(plan, assumptions); continue }
        shared.releaseRoutes(ship)
        dry++
        if (plan == null && dwell.toMinutes() < Strategy.WO_DWELL_MINUTES && dry < 3) { status("waiting", "no route pays in $system yet; checking again in 5 minutes"); clock.sleep(5.minutes); continue }
        // Drained, or the dwell is up: the next untouched system in reach.
        val snap = snapshot()
        fun reach(s: model.system.System) = Strategy.warpReach(me.fuel.capacity.toDouble(), Strategy.fuelLikely(snap, s))
        val next = Strategy.warpTradeTarget(shared.plan, snap, system, ::reach).firstOrNull { (s, _) -> !shared.claimedByOther(s.symbol, ship) }
        if (next == null) { status("waiting", "no untouched warp-only system within reach of $system; checking again in 30 minutes"); clock.sleep(30.minutes); continue }
        val (dest, distance) = next
        phase("load out", "for ${dest.symbol}") { outboundLoad(system) }
        phase("refuel") { fillTank() }
        val target = dest.waypoints.firstOrNull { it.type == WaypointType.JUMP_GATE } ?: dest.waypoints.firstOrNull { it.type == WaypointType.PLANET } ?: dest.waypoints.first()
        shared.claim(ship, dest.symbol)
        val arrived = phase("warp", "to ${dest.symbol} (${distance.toInt()} away, ${dest.waypoints.size} waypoints)") {
            try { warpTo(ship, target.symbol); true } catch (e: VerbFailure) { status(detail = "could not warp to ${dest.symbol}: ${e.message}"); false }
        }
        if (!arrived) { shared.release(ship); clock.sleep(5.minutes) }
    }
}

/** One load of [plan]: to the source, buy while it pays, to the destination, sell, refuel. The trade behaviour's cycle. */
private suspend fun BehaviourScope.tradeOnce(plan: TradePlan, assumptions: TradingAssumptions) {
    status("plan", plan.summary())
    phase("travel to source", plan.source.symbol) {
        ensureFuel(Travel.fuelCost(plan.legToSource, FlightMode.CRUISE) + 10)
        travelTo(plan.source.symbol)
    }
    val bought = phase("buy", "${plan.good} at ${plan.source.symbol}") {
        dock(ship)
        val live = refreshMarket(plan.source.symbol)
        if (Trading.stillPays(plan, live, assumptions) == null) { status(detail = "${plan.good} at ${plan.source.symbol} no longer pays"); 0 } else buyLoad(plan, live, assumptions)
    }
    if (bought == 0) { clock.sleep(30.seconds); return }
    phase("travel to destination", plan.destination.symbol) {
        ensureFuel(Travel.fuelCost(plan.legToDestination, FlightMode.CRUISE) + 10)
        travelTo(plan.destination.symbol)
    }
    phase("sell", "${plan.good} at ${plan.destination.symbol}") {
        dock(ship)
        val market = refreshMarket(plan.destination.symbol)
        val (toSell, toDrop) = Selling.split(me.cargo, market)
        var earned = 0L
        toSell.forEach { line -> earned += sell(ship, line.symbol, line.units).credits; status(detail = "sold ${line.units} ${line.symbol}; +$earned this load") }
        toDrop.forEach { line -> jettison(ship, line.symbol, line.units) }
    }
    phase("refuel", "at ${plan.destination.symbol}") { try { refuel(ship) } catch (e: VerbFailure) { status(detail = "could not refuel: ${e.message}") } }
}

/** Probes bought at the system's own yard to chart it; they stay behind, parked, when it is done. */
private suspend fun BehaviourScope.kitProbes(system: String) {
    val yard = snapshot().waypointsIn(system).firstOrNull { it.hasShipyard } ?: run { status(detail = "$system has no yard; charting is left to the ship's own passes"); return }
    shared.editPlan("chart kit for $system") { p -> p.withGoal(FleetGoal(ShipType.SHIP_PROBE, Strategy.WO_KIT_PROBES, reserve = Strategy.GALAXY_RESERVE, system = system)) }
    phase("kit", "buying chart probes at ${yard.symbol}") {
        travelTo(yard.symbol)
        dock(ship)
        refreshShipyard(yard.symbol)
        var bought = 0
        while (maybeExpand() != null) bought++
        status(detail = "$bought probe(s) bought to chart $system")
    }
}

/**
 * Fills the hold with the export here that the rest of the galaxy pays most for, when the expected
 * margin clears [Strategy.WO_OUTBOUND_MARGIN]: the load is sold on arrival at whatever pays there.
 */
private suspend fun BehaviourScope.outboundLoad(system: String) {
    val snap = snapshot()
    val pick = snap.pricedMarketsIn(system).flatMap { m -> m.tradeGoods.filter { it.type == model.market.TradeGoodType.EXPORT && it.supply >= model.market.SupplyLevel.MODERATE }.map { m to it } }
        .mapNotNull { (m, offer) ->
            val expected = Strategy.galaxySellPrice(snap, offer.symbol) ?: return@mapNotNull null
            val margin = (expected - offer.purchasePrice).toDouble() / offer.purchasePrice
            if (margin < Strategy.WO_OUTBOUND_MARGIN) null else Triple(m, offer, margin * minOf(me.cargo.capacity, offer.tradeVolume * 4) * offer.purchasePrice)
        }
        .maxByOrNull { it.third } ?: run { status(detail = "nothing here is worth carrying out"); return }
    val (market, offer, _) = pick
    travelTo(market.symbol)
    dock(ship)
    val affordable = ((agent().credits * 0.5) / offer.purchasePrice).toInt()
    val units = minOf(me.cargoSpaceLeft, affordable, offer.tradeVolume * 4)
    if (units <= 0) return
    try {
        val sale = purchase(ship, offer.symbol, units)
        status(detail = "carrying ${sale.units} ${offer.symbol} out at ${sale.averagePrice.toInt()}; the galaxy pays about ${Strategy.galaxySellPrice(snap, offer.symbol)}")
    } catch (e: VerbFailure) { status(detail = "could not load ${offer.symbol}: ${e.message}") }
}

/** Puts the ship at [yard]: through the gates when the system has one, by warp when it can, else not at all. */
internal suspend fun BehaviourScope.reachYard(yard: String): Boolean {
    val system = yard.substringBeforeLast('-')
    // A ship can stand in a system whose waypoints were never loaded (A2 in X1-PA74 on 2026-09-09): load them before asking where it is.
    if (snapshot().waypointsIn(me.nav.systemSymbol).isEmpty()) phase("map", me.nav.systemSymbol) { loadSystem(me.nav.systemSymbol) }
    if (me.nav.systemSymbol != system) {
        val snap = snapshot()
        val hasGate = snap.waypointsIn(me.nav.systemSymbol).any { it.type == WaypointType.JUMP_GATE }
        if (hasGate) {
            if (!phase("travel", "to $system") { goToSystem(system) }) { status("waiting", "no route to $system"); return false }
        } else if (me.canWarp) {
            fillTank()
            val from = snap.systems[me.nav.systemSymbol] ?: return false
            val there = snap.systems[system]
            val fuel = me.fuel.current.toDouble()
            if (there != null && Travel.distance(from.x.toInt(), from.y.toInt(), there.x.toInt(), there.y.toInt()) <= Strategy.warpReach(fuel, true)) {
                phase("warp", "to $system") { warpTo(ship, yard) }
            } else {
                val home = Strategy.warpHome(shared.plan, snap, from) { s -> Strategy.warpReach(fuel, Strategy.fuelLikely(snap, s)) } ?: run { status("waiting", "no gate in reach to leave ${from.symbol} by"); return false }
                val gate = snap.waypointsIn(home.first.symbol).firstOrNull { it.type == WaypointType.JUMP_GATE }?.symbol ?: home.first.waypoints.first { it.type == WaypointType.JUMP_GATE }.symbol
                phase("warp home", "to ${home.first.symbol}, the nearest gate") { warpTo(ship, gate) }
                return false
            }
        } else { status("waiting", "${me.nav.systemSymbol} has no gate and this ship cannot warp"); return false }
    }
    if (me.nav.waypointSymbol != yard) phase("travel", "to $yard") { travelTo(yard) }
    return true
}

private fun freeSlots(ship: Ship): Long = ship.frame.moduleSlots - ship.modules.sumOf { it.requirements.slots }

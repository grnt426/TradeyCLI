package behaviour

import engine.ShipStatus
import engine.Travel
import engine.VerbFailure
import engine.Verbs
import io.github.oshai.kotlinlogging.KotlinLogging
import model.ship.FlightMode
import model.ship.Ship
import model.system.Waypoint
import java.util.concurrent.ConcurrentHashMap
import plan.Goals
import plan.Plan
import plan.Stage

private val logger = KotlinLogging.logger {}

/**
 * What a behaviour runs inside: its ship, its parameters, the verbs, and `phase`, which publishes
 * where the behaviour is so `ships` can show it and a failure can name the step it died in.
 */
class BehaviourScope(
    val ship: String,
    val behaviour: String,
    val params: Map<String, String>,
    val verbs: Verbs,
    val shared: SharedState,
) : Verbs by verbs {

    /** The phase the behaviour is in right now, for failure reports. */
    @Volatile
    var currentPhase: String = "starting"
        private set

    /** The ship as the world knows it right now. */
    val me: Ship get() = verbs.ship(ship)

    val here: Waypoint get() = verbs.waypoint(me.nav.waypointSymbol)

    suspend fun <T> phase(name: String, detail: String = "", block: suspend () -> T): T {
        status(name, detail)
        return block()
    }

    /** Updates the detail line without changing phase. */
    suspend fun status(phase: String = currentPhase, detail: String) {
        currentPhase = phase
        logger.info { "$ship $behaviour: $phase ${detail.ifEmpty { "" }}".trimEnd() }
        verbs.setStatus(ship, ShipStatus(behaviour, phase, detail, clock.now()), paramsJson())
    }

    fun param(name: String): String? = params[name]?.takeIf { it.isNotBlank() }

    fun distanceTo(waypoint: String): Double {
        val from = here
        val to = verbs.waypoint(waypoint)
        return Travel.distance(from.x, from.y, to.x, to.y)
    }

    /**
     * Goes to [waypoint], topping up first when the tank would not cover the trip and a market is
     * at hand, and drifting when it still would not. Probes fly free.
     */
    /**
     * Goes to [waypoint] on cruise. When the tank cannot cover the leg it tops up here if it can,
     * and otherwise routes through fuel stops ([engine.FuelRoute]), refuelling at each. Drifting,
     * ten times slower, is the last resort when no chain of stops exists at all; on 2026-09-05 a
     * shuttle with a full tank drifted for hours from the far corner of the home system because
     * the direct leg was longer than its tank and nothing routed it through a stop.
     */
    suspend fun travelTo(waypoint: String, mode: FlightMode = FlightMode.CRUISE): Ship {
        val s = me
        if (s.nav.waypointSymbol == waypoint) return s
        if (!s.usesFuel) return navigateTo(ship, waypoint, mode)
        val needed = Travel.fuelCost(distanceTo(waypoint), mode)
        if (s.fuel.current < needed && here.hasMarket) {
            try {
                refuel(ship)
            } catch (e: VerbFailure) {
                logger.warn { "$ship could not top up at ${here.symbol}: ${e.message}" }
            }
        }
        if (me.fuel.current >= needed) return navigateTo(ship, waypoint, mode)
        val route = fuelRoute(waypoint)
        if (route == null) {
            // Stranded: nothing is within the tank. Drift to the nearest fuel station rather than all the way to the
            // target, refuel, and cruise the rest; a surveyor restarted in deep space on 2026-09-06 faced a four-hour drift.
            val from = here
            val to = verbs.waypoint(waypoint)
            val snap = snapshot()
            val stops = snap.waypointsIn(from.systemSymbol).filter { it.hasMarket && snap.markets[it.symbol]?.trades(model.market.TradeSymbol.FUEL) != false }
            val station = stops.filter { it.symbol != from.symbol && it.symbol != waypoint }.minByOrNull { Travel.distance(from.x, from.y, it.x, it.y) }
            // Worth it only when the rest can be cruised from there: drift is ten times slower, so a detour must save more drift than it adds.
            val worth = station != null && engine.FuelRoute.plan(station, to, stops, s.fuel.capacity.toLong(), s.fuel.capacity.toLong(), true) != null &&
                10 * Travel.distance(from.x, from.y, station.x, station.y) + Travel.distance(station.x, station.y, to.x, to.y) < 10 * distanceTo(waypoint)
            if (worth) {
                status(detail = "no fuel stop within reach; drifting to ${station!!.symbol} to refuel, then cruising on to $waypoint")
                navigateTo(ship, station.symbol, FlightMode.DRIFT)
                try { dock(ship); refuel(ship) } catch (e: VerbFailure) { logger.warn { "$ship could not refuel at ${station.symbol}: ${e.message}" } }
                return travelTo(waypoint, mode)
            }
            status(detail = "no fuel stop within reach on the way to $waypoint; drifting")
            return navigateTo(ship, waypoint, FlightMode.DRIFT)
        }
        for (stop in route) {
            navigateTo(ship, stop.symbol, FlightMode.CRUISE)
            try { refuel(ship) } catch (e: VerbFailure) { logger.warn { "$ship could not refuel at ${stop.symbol}: ${e.message}" } }
        }
        val last = Travel.fuelCost(distanceTo(waypoint), FlightMode.CRUISE)
        return if (me.fuel.current >= last) navigateTo(ship, waypoint, FlightMode.CRUISE) else navigateTo(ship, waypoint, FlightMode.DRIFT)
    }

    /** The fuel stops to pass through on the way to [waypoint], empty when direct, null when no chain of stops exists. */
    fun fuelRoute(waypoint: String): List<Waypoint>? {
        val s = me
        val from = here
        val to = verbs.waypoint(waypoint)
        val snap = snapshot()
        val stops = snap.waypointsIn(from.systemSymbol).filter { it.hasMarket && snap.markets[it.symbol]?.trades(model.market.TradeSymbol.FUEL) != false }
        return engine.FuelRoute.plan(from, to, stops, s.fuel.capacity.toLong(), s.fuel.current.toLong(), from.hasMarket)
    }

    /** Kept for callers that say "via": [travelTo] routes through fuel stops itself now. */
    suspend fun travelVia(waypoint: String): Ship = travelTo(waypoint)

    /** Refuels when the tank is below what [fuelNeeded] units of travel would take, if a market is here. */
    suspend fun ensureFuel(fuelNeeded: Long) {
        val s = me
        if (!s.usesFuel || s.fuel.current >= fuelNeeded.coerceAtMost(s.fuel.capacity) || !here.hasMarket) return
        try {
            refuel(ship)
        } catch (e: VerbFailure) {
            logger.warn { "$ship could not refuel at ${here.symbol}: ${e.message}" }
        }
    }

    private fun paramsJson(): String = params.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }

    /**
     * The expansion policy: if the ship is at a shipyard that lists a type the plan still wants,
     * and the bank stays above the goal's reserve, buy one and hand it to the supervisor.
     * Returns the new ship, or null when nothing was bought.
     */
    suspend fun maybeExpand(): Ship? {
        val goals = shared.goals.fleet.filter { it.buyableAt(me.nav.systemSymbol, shared.plan) }
        if (goals.isEmpty() || !here.hasShipyard) return null
        if (!shared.buying.compareAndSet(false, true)) return null
        try {
            val yard = refreshShipyard(here.symbol)
            val fleet = snapshot().ships.values
            for (goal in goals) {
                val owned = goal.owned(fleet)
                if (owned >= goal.count) continue
                val price = yard.priceOf(goal.type) ?: continue
                val reserve = knowledge.Strategy.purchaseReserve(goal.reserve, snapshot())
                if (agent().credits - price < reserve) {
                    status(detail = "would buy ${goal.type} at $price but the bank must keep $reserve (working capital for the traders)")
                    continue
                }
                if (price >= knowledge.Strategy.PACED_PURCHASE_PRICE) {
                    shared.lastPurchaseAt?.let { last ->
                        val minutes = java.time.Duration.between(last, clock.now()).toMinutes()
                        if (minutes < knowledge.Strategy.MINUTES_BETWEEN_PURCHASES) { status(detail = "bought a ship ${minutes} min ago; the next waits"); return null }
                    }
                    shared.lastPurchaseAt = clock.now()
                }
                if (!me.isDocked) dock(ship)
                val bought = purchaseShip(goal.type, here.symbol)
                status(detail = "bought ${bought.symbol} (${goal.type}) for $price; ${owned + 1}/${goal.count}" + (goal.system?.let { " for $it" } ?: ""))
                goal.system?.let { s -> shared.plan.system(s)?.let { r -> shared.editPlan("rush spend in $s") { p -> p.withSystem((p.system(s) ?: r).copy(spent = (p.system(s) ?: r).spent + price)) } } }
                shared.onShipPurchased(bought, goal.system)
                return bought
            }
            return null
        } finally {
            shared.buying.set(false)
        }
    }

    companion object {
        /** A goal is bought at a yard in its own system, anywhere when it names none, or at any yard when its system has no yard to buy at. */
        fun plan.FleetGoal.buyableAt(yardSystem: String, plan: Plan): Boolean =
            system == null || system == yardSystem || plan.system(system!!)?.let { it.yard == null && it.stage != Stage.CASCADE } == true

        /** The shipyard type a ship was bought as, read back from its frame and fittings. */
        fun shipTypeOf(ship: Ship): model.ship.ShipType? = when (ship.frame.symbol) {
            "FRAME_PROBE" -> model.ship.ShipType.SHIP_PROBE
            "FRAME_DRONE" -> when {
                ship.canMine -> model.ship.ShipType.SHIP_MINING_DRONE
                ship.canSurvey -> model.ship.ShipType.SHIP_SURVEYOR
                ship.canSiphon -> model.ship.ShipType.SHIP_SIPHON_DRONE
                else -> null
            }
            "FRAME_SHUTTLE" -> model.ship.ShipType.SHIP_LIGHT_SHUTTLE
            "FRAME_LIGHT_FREIGHTER" -> model.ship.ShipType.SHIP_LIGHT_HAULER
            "FRAME_HEAVY_FREIGHTER" -> model.ship.ShipType.SHIP_HEAVY_FREIGHTER
            "FRAME_MINER" -> model.ship.ShipType.SHIP_ORE_HOUND
            "FRAME_FRIGATE" -> model.ship.ShipType.SHIP_COMMAND_FRIGATE
            "FRAME_EXPLORER" -> model.ship.ShipType.SHIP_EXPLORER
            "FRAME_INTERCEPTOR" -> model.ship.ShipType.SHIP_INTERCEPTOR
            "FRAME_BULK_FREIGHTER" -> model.ship.ShipType.SHIP_BULK_FREIGHTER
            else -> null
        }
    }
}

/** State several behaviours coordinate through: claims on waypoints, the plan's goals, and what to do with a ship just bought. */
class SharedState {
    val claims = ConcurrentHashMap<String, String>()

    @Volatile
    var goals: Goals = Goals()

    /** The plan the supervisor is running, for behaviours that read more than their own parameters. */
    @Volatile
    var plan: Plan = Plan()

    /** Set by the supervisor: gives a newly bought ship an assignment, for the system it was bought for. */
    @Volatile
    var onShipPurchased: (Ship, String?) -> Unit = { _, _ -> }

    /** Set by the supervisor: moves the plan to a new phase and saves it. */
    @Volatile
    var onPhaseChanged: (plan.Phase) -> Unit = {}

    fun advancePhase(to: plan.Phase) { if (plan.phase != to) onPhaseChanged(to) }

    /** Set by the supervisor: applies and saves a plan a behaviour rewrote (a fleet goal raised for the gate rush). */
    @Volatile
    var onPlanEdited: (edit: (Plan) -> Plan, why: String) -> Unit = { _, _ -> }

    fun editPlan(why: String, edit: (Plan) -> Plan) = onPlanEdited(edit, why)

    /** Units of a site's material a ship has claimed to deliver (bought or about to buy), keyed "site/material/ship". */
    private val deliveries = ConcurrentHashMap<String, Int>()

    fun reserveDelivery(ship: String, site: String, material: String, units: Int) {
        deliveries.entries.removeIf { it.key.endsWith("/$ship") && it.key.startsWith("$site/") }
        if (units > 0) deliveries["$site/$material/$ship"] = units
    }

    fun releaseDelivery(ship: String) = deliveries.entries.removeIf { it.key.endsWith("/$ship") }

    /** What other ships already have in hand for this site and material. */
    fun reservedByOthers(ship: String, site: String, material: String): Int =
        deliveries.filterKeys { it.startsWith("$site/$material/") && !it.endsWith("/$ship") }.values.sum()

    /** Gates a jump was refused to because they are still under construction (API 4262): both ends must be built. */
    val unreachableGates: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** The gate network as read so far: gate waypoint -> its connections, for [knowledge.GateGraph] routes across several jumps. */
    val gates = ConcurrentHashMap<String, List<String>>()

    /** When the last ship was bought, so purchases are paced and their effect on income is seen. */
    @Volatile
    var lastPurchaseAt: java.time.Instant? = null

    /** Drones sitting on rocks (mineInPlace), by ship symbol, for the collector. */
    val parked = ConcurrentHashMap<String, String>()

    /** The producers' take-rate buckets, shared by every ship so three haulers do not each take a full load. */
    val takeBudget = knowledge.TakeBudget()

    /** The construction site's bill as the gate hauler last read it, for the summary. */
    @Volatile
    var constructionBill: List<model.ConstructionMaterial>? = null

    /** Whether any behaviour is buying a ship right now, so two traders at two yards do not both spend the reserve. */
    val buying = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Goods being bought or sold at a market by a trader: "GOOD@WAYPOINT" to ship, so two traders never work the same good at the same market. */
    val routes = ConcurrentHashMap<String, String>()

    fun claimRoute(ship: String, vararg keys: String) {
        releaseRoutes(ship)
        keys.forEach { routes[it] = ship }
    }

    fun releaseRoutes(ship: String) {
        routes.entries.removeIf { it.value == ship }
    }

    fun routeTakenByOther(key: String, ship: String): Boolean = routes[key]?.let { it != ship } ?: false

    /** Claims [waypoint] for [ship] unless another ship holds it. */
    fun claim(ship: String, waypoint: String): Boolean {
        claims.entries.removeIf { it.value == ship }
        return claims.putIfAbsent(waypoint, ship)?.let { it == ship } ?: true
    }

    fun release(ship: String) {
        claims.entries.removeIf { it.value == ship }
        releaseRoutes(ship)
    }

    fun claimedByOther(waypoint: String, ship: String): Boolean = claims[waypoint]?.let { it != ship } ?: false
}

/** A behaviour gave up for a reason it can name. The supervisor reports it and restarts with backoff. */
class BehaviourFailure(message: String) : Exception(message)

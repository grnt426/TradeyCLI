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
    suspend fun travelTo(waypoint: String, mode: FlightMode = FlightMode.CRUISE): Ship {
        val s = me
        if (s.nav.waypointSymbol == waypoint) return s
        if (!s.usesFuel) return navigateTo(ship, waypoint, mode)
        val needed = Travel.fuelCost(distanceTo(waypoint), mode)
        if (s.fuel.current < needed && here.hasMarket) {
            try {
                refuel(ship)
            } catch (e: VerbFailure) {
                logger.warn { "$ship could not top up at ${here.symbol}: ${e.message}; drifting" }
            }
        }
        return if (me.fuel.current >= needed) navigateTo(ship, waypoint, mode) else navigateTo(ship, waypoint, FlightMode.DRIFT)
    }

    /**
     * Goes to [waypoint] on cruise, stopping to refuel at the market that adds the least distance
     * when the tank cannot cover the leg. Probes fly free. Drifts only when no stop is in reach.
     */
    suspend fun travelVia(waypoint: String): Ship {
        val s = me
        if (s.nav.waypointSymbol == waypoint || !s.usesFuel) return travelTo(waypoint)
        val capacity = s.fuel.capacity
        if (Travel.fuelCost(distanceTo(waypoint), FlightMode.CRUISE) <= capacity) {
            ensureFuel(Travel.fuelCost(distanceTo(waypoint), FlightMode.CRUISE) + 5)
            return travelTo(waypoint)
        }
        val from = here
        val to = verbs.waypoint(waypoint)
        val snap = snapshot()
        val stop = snap.waypointsIn(from.systemSymbol)
            .filter { it.hasMarket && it.symbol != from.symbol && it.symbol != to.symbol && snap.markets[it.symbol]?.trades(model.market.TradeSymbol.FUEL) != false }
            .map { it to (Travel.distance(from.x, from.y, it.x, it.y) to Travel.distance(it.x, it.y, to.x, to.y)) }
            .filter { (_, legs) -> Travel.fuelCost(legs.first, FlightMode.CRUISE) <= capacity && Travel.fuelCost(legs.second, FlightMode.CRUISE) <= capacity }
            .minByOrNull { (_, legs) -> legs.first + legs.second }
        if (stop == null) return travelTo(waypoint)
        ensureFuel(Travel.fuelCost(stop.second.first, FlightMode.CRUISE) + 5)
        travelTo(stop.first.symbol)
        refuel(ship)
        return travelTo(waypoint)
    }

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
        val goals = shared.goals.fleet
        if (goals.isEmpty() || !here.hasShipyard) return null
        if (!shared.buying.compareAndSet(false, true)) return null
        try {
            val yard = refreshShipyard(here.symbol)
            val fleet = snapshot().ships.values
            for (goal in goals) {
                val owned = fleet.count { shipTypeOf(it) == goal.type }
                if (owned >= goal.count) continue
                val price = yard.priceOf(goal.type) ?: continue
                if (agent().credits - price < goal.reserve) {
                    status(detail = "would buy ${goal.type} at $price but the reserve is ${goal.reserve}")
                    continue
                }
                if (!me.isDocked) dock(ship)
                val bought = purchaseShip(goal.type, here.symbol)
                status(detail = "bought ${bought.symbol} (${goal.type}) for $price; ${owned + 1}/${goal.count}")
                shared.onShipPurchased(bought)
                return bought
            }
            return null
        } finally {
            shared.buying.set(false)
        }
    }

    companion object {
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

    /** Set by the supervisor: gives a newly bought ship an assignment. */
    @Volatile
    var onShipPurchased: (Ship) -> Unit = {}

    /** Set by the supervisor: moves the plan to a new phase and saves it. */
    @Volatile
    var onPhaseChanged: (plan.Phase) -> Unit = {}

    fun advancePhase(to: plan.Phase) { if (plan.phase != to) onPhaseChanged(to) }

    /** Set by the supervisor: applies and saves a plan a behaviour rewrote (a fleet goal raised for the gate rush). */
    @Volatile
    var onPlanEdited: (edit: (Plan) -> Plan, why: String) -> Unit = { _, _ -> }

    fun editPlan(why: String, edit: (Plan) -> Plan) = onPlanEdited(edit, why)

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

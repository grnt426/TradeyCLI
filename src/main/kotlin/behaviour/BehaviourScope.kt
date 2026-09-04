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
        if (s.fuel.current < needed && here.hasMarket) refuel(ship)
        return if (me.fuel.current >= needed) navigateTo(ship, waypoint, mode) else navigateTo(ship, waypoint, FlightMode.DRIFT)
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
}

/** State several behaviours coordinate through: which waypoint each ship is heading for or working. */
class SharedState {
    val claims = ConcurrentHashMap<String, String>()

    /** Claims [waypoint] for [ship] unless another ship holds it. */
    fun claim(ship: String, waypoint: String): Boolean {
        claims.entries.removeIf { it.value == ship }
        return claims.putIfAbsent(waypoint, ship)?.let { it == ship } ?: true
    }

    fun release(ship: String) {
        claims.entries.removeIf { it.value == ship }
    }

    fun claimedByOther(waypoint: String, ship: String): Boolean = claims[waypoint]?.let { it != ship } ?: false
}

/** A behaviour gave up for a reason it can name. The supervisor reports it and restarts with backoff. */
class BehaviourFailure(message: String) : Exception(message)

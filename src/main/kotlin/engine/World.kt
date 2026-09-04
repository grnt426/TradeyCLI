package engine

import model.Agent
import model.ServerStatus
import model.Shipyard
import model.actions.Survey
import model.market.Market
import model.ship.Ship
import model.system.OrbitalNames
import model.system.System
import model.system.Waypoint
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine's working state. The engine thread and the verb layer write it; everyone else reads
 * a [Snapshot]. Every map is concurrent and every value immutable, so a writer replaces whole
 * entries and a reader never sees a half-updated one.
 */
class World {
    @Volatile
    var agent: Agent? = null

    @Volatile
    var serverStatus: ServerStatus? = null

    val systems = ConcurrentHashMap<String, System>()
    val waypoints = ConcurrentHashMap<String, Waypoint>()
    val markets = ConcurrentHashMap<String, Market>()
    val shipyards = ConcurrentHashMap<String, Shipyard>()
    val ships = ConcurrentHashMap<String, Ship>()

    /** Surveys by signature. Expired ones are dropped on read. */
    val surveys = ConcurrentHashMap<String, Survey>()

    /** What each ship's behaviour is doing, by ship symbol. */
    val shipStatus = ConcurrentHashMap<String, ShipStatus>()

    fun hqSystemSymbol(): String? = agent?.let { OrbitalNames.getSectorSystem(it.headquarters) }

    fun snapshot(version: Long): Snapshot = Snapshot(
        version = version,
        agent = agent,
        resetDate = serverStatus?.resetDate,
        nextReset = serverStatus?.serverResets?.next,
        hqSystem = hqSystemSymbol(),
        systems = HashMap(systems),
        waypoints = HashMap(waypoints),
        markets = HashMap(markets),
        shipyards = HashMap(shipyards),
        ships = HashMap(ships),
        surveys = surveys.values.toList(),
        shipStatus = HashMap(shipStatus),
    )
}

/** An immutable view of the world at one moment. Cheap to hold, safe to read from any thread. */
data class Snapshot(
    val version: Long,
    val agent: Agent?,
    val resetDate: String?,
    val nextReset: String?,
    val hqSystem: String?,
    val systems: Map<String, System>,
    val waypoints: Map<String, Waypoint>,
    val markets: Map<String, Market>,
    val shipyards: Map<String, Shipyard>,
    val ships: Map<String, Ship>,
    val surveys: List<Survey> = emptyList(),
    val shipStatus: Map<String, ShipStatus> = emptyMap(),
) {
    fun waypointsIn(system: String): List<Waypoint> = waypoints.values.filter { it.systemSymbol == system }.sortedBy { it.symbol }
    fun marketsIn(system: String): List<Market> = markets.values.filter { OrbitalNames.getSectorSystem(it.symbol) == system }.sortedBy { it.symbol }
    fun shipyardsIn(system: String): List<Shipyard> = shipyards.values.filter { OrbitalNames.getSectorSystem(it.symbol) == system }.sortedBy { it.symbol }
    fun asteroidsIn(system: String): List<Waypoint> = waypointsIn(system).filter { it.isMineable }

    fun waypoint(symbol: String): Waypoint = waypoints[symbol] ?: error("Unknown waypoint $symbol")

    /** Markets in [system] whose prices have been seen, freshest first. */
    fun pricedMarketsIn(system: String): List<Market> = marketsIn(system).filter { it.hasPrices }.sortedByDescending { it.lastRead }

    fun validSurveysFor(waypoint: String, now: Instant): List<Survey> = surveys.filter { it.symbol == waypoint && it.isValidAt(now) }
}

/** Something that happened in the engine, for the UI and the log. */
sealed interface Event {
    data class Booted(val agent: String, val resetDate: String) : Event
    data class ShipsLoaded(val count: Int) : Event
    data class SystemLoaded(val system: String, val waypoints: Int, val markets: Int, val shipyards: Int) : Event
    data class MarketUpdated(val symbol: String) : Event
    data class Warning(val message: String) : Event
    data class Failure(val message: String, val cause: Throwable?) : Event

    data class PhaseChanged(val ship: String, val behaviour: String, val phase: String, val detail: String) : Event
    data class BehaviourStarted(val ship: String, val behaviour: String) : Event
    data class BehaviourFinished(val ship: String, val behaviour: String) : Event
    data class BehaviourFailed(val ship: String, val behaviour: String, val reason: String, val restartIn: String?) : Event
    data class Extracted(val ship: String, val waypoint: String, val good: String, val units: Int, val cargo: String) : Event
    data class Sold(val ship: String, val waypoint: String, val good: String, val units: Int, val credits: Long) : Event
    data class Bought(val ship: String, val waypoint: String, val good: String, val units: Int, val credits: Long) : Event
    data class Refueled(val ship: String, val waypoint: String, val units: Int, val credits: Long) : Event
    data class Surveyed(val ship: String, val waypoint: String, val surveys: Int) : Event
    data class ShipPurchased(val ship: String, val type: String, val credits: Long) : Event
}

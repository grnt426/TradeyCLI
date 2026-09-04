package engine

import model.Agent
import model.ServerStatus
import model.Shipyard
import model.market.Market
import model.ship.Ship
import model.system.OrbitalNames
import model.system.System
import model.system.Waypoint
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine's working state. Only the engine thread writes it (and, until they are rebuilt, the
 * parked scripts through the GameState facade). Everyone else reads a [Snapshot].
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
) {
    fun waypointsIn(system: String): List<Waypoint> = waypoints.values.filter { it.systemSymbol == system }.sortedBy { it.symbol }
    fun marketsIn(system: String): List<Market> = markets.values.filter { OrbitalNames.getSectorSystem(it.symbol) == system }.sortedBy { it.symbol }
    fun shipyardsIn(system: String): List<Shipyard> = shipyards.values.filter { OrbitalNames.getSectorSystem(it.symbol) == system }.sortedBy { it.symbol }
}

/** Something that happened in the engine, for the UI and the log. */
sealed interface Event {
    data class Booted(val agent: String, val resetDate: String) : Event
    data class ShipsLoaded(val count: Int) : Event
    data class SystemLoaded(val system: String, val waypoints: Int, val markets: Int, val shipyards: Int) : Event
    data class MarketUpdated(val symbol: String) : Event
    data class Warning(val message: String) : Event
    data class Failure(val message: String, val cause: Throwable?) : Event
}

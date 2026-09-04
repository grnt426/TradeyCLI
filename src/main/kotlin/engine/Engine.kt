package engine

import api.ApiClient
import api.ApiError
import api.RequestPacer
import api.SpaceTradersApi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import model.Agent
import model.WaypointTraitSymbol
import model.market.Market
import model.system.OrbitalNames
import model.system.System
import model.system.Waypoint
import storage.AgentStore
import storage.Layout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private val logger = KotlinLogging.logger {}

/**
 * Owns the game state. Every mutation happens on one engine thread; API calls suspend on that
 * thread rather than block it, so work interleaves without locks. Readers take [state]; the UI
 * and line mode never touch [world] directly.
 *
 * Nothing here starts ship automation; that arrives with the scripting overhaul.
 */
class Engine(
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("engine")),
    val pacer: RequestPacer = RequestPacer(scope),
    private val apiFactory: (token: String) -> SpaceTradersApi = { token -> SpaceTradersApi(ApiClient(token, pacer)) },
    private val storeFactory: (agentSymbol: String, resetDate: String) -> AgentStore =
        { symbol, reset -> AgentStore.open(Layout.agentDir(symbol), symbol, reset) },
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "engine").apply { isDaemon = true } }
    private val engineThread = executor.asCoroutineDispatcher()

    val world = World()

    private var version = 0L
    private val _state = MutableStateFlow(world.snapshot(0))
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile
    var api: SpaceTradersApi? = null
        private set

    @Volatile
    var store: AgentStore? = null
        private set

    private val systemLoads = ConcurrentHashMap<String, Deferred<Unit>>()

    val snapshot: Snapshot get() = _state.value

    /**
     * Connects with [token], learns which agent and reset this is, opens the store, loads what is
     * cached, refreshes the fleet and the home system, and returns. Waypoints, markets and
     * shipyards of the home system keep loading in the background afterwards.
     *
     * [onAgentKnown] runs as soon as the agent is identified, before storage is opened, so the
     * caller can settle where the token lives.
     */
    suspend fun boot(
        token: String,
        progress: (String) -> Unit = {},
        onAgentKnown: suspend (Agent) -> Unit = {},
    ): Unit = withContext(engineThread) {
        progress("Connecting")
        val api = apiFactory(token).also { this@Engine.api = it }
        api.client.listener = { record -> store?.let { s -> scope.launch { s.logRequest(record) } } }

        progress("Checking server status")
        val status = api.getStatus()

        progress("Loading agent")
        val agent = api.getMyAgent()
        world.agent = agent
        world.serverStatus = status
        onAgentKnown(agent)
        publish()

        progress("Opening storage for reset ${status.resetDate}")
        val store = withContext(Dispatchers.IO) { storeFactory(agent.symbol, status.resetDate) }.also { this@Engine.store = it }
        store.putAgent(agent)

        progress("Loading cached data")
        store.listSystems().forEach { world.systems[it.symbol] = it }
        store.listWaypoints().forEach { world.waypoints[it.symbol] = it }
        store.listMarkets().forEach { world.markets[it.symbol] = it }
        store.listShipyards().forEach { world.shipyards[it.symbol] = it }
        store.listShips().forEach { world.ships[it.symbol] = it }
        publish()

        progress("Loading ships")
        refreshShips()

        val hq = world.hqSystemSymbol() ?: error("Agent ${agent.symbol} has no headquarters")
        progress("Loading home system $hq")
        ensureSystem(hq)
        emit(Event.Booted(agent.symbol, status.resetDate))
        ensureSystemLoaded(hq)
    }

    suspend fun refreshAgent(): Agent = onEngine {
        val agent = api().getMyAgent()
        world.agent = agent
        store?.putAgent(agent)
        publish()
        agent
    }

    suspend fun refreshShips(): List<model.ship.Ship> = onEngine {
        val fleet = api().listMyShips()
        world.ships.clear()
        fleet.forEach { world.ships[it.symbol] = it }
        store?.putShips(fleet)
        publish()
        emit(Event.ShipsLoaded(fleet.size))
        fleet
    }

    /** The system record itself (position, star, waypoint symbols), from cache or the API. */
    suspend fun ensureSystem(symbol: String): System = onEngine {
        world.systems[symbol] ?: api().getSystem(symbol).also {
            world.systems[symbol] = it
            store?.putSystem(it)
            publish()
        }
    }

    /**
     * Starts loading a system's waypoints, markets and shipyards unless that is already under way
     * or done. Returns the job so callers that need the result can await it.
     */
    fun ensureSystemLoaded(symbol: String): Deferred<Unit> =
        systemLoads.getOrPut(symbol) { scope.async { loadSystemContents(symbol) } }

    suspend fun awaitSystem(symbol: String) = ensureSystemLoaded(symbol).await()

    /** Fetches a system's waypoints again, replacing the cached ones. */
    suspend fun refreshWaypoints(system: String): List<Waypoint> = onEngine {
        val list = api().listSystemWaypoints(system)
        list.forEach { world.waypoints[it.symbol] = it }
        store?.putWaypoints(list)
        publish()
        list
    }

    suspend fun refreshMarket(waypoint: String): Market = onEngine {
        val market = api().getMarket(OrbitalNames.getSectorSystem(waypoint), waypoint)
        world.markets[market.symbol] = market
        store?.putMarket(market)
        publish()
        emit(Event.MarketUpdated(market.symbol))
        market
    }

    suspend fun refreshShipyard(waypoint: String): model.Shipyard = onEngine {
        val shipyard = api().getShipyard(OrbitalNames.getSectorSystem(waypoint), waypoint)
        world.shipyards[shipyard.symbol] = shipyard
        store?.putShipyard(shipyard)
        publish()
        shipyard
    }

    fun shutdown() {
        scope.cancel()
        runCatching { api?.client?.close() }
        runCatching { store?.close() }
        executor.shutdown()
    }

    private suspend fun loadSystemContents(system: String): Unit = onEngine {
        try {
            val waypoints = world.waypoints.values.filter { it.systemSymbol == system }.ifEmpty {
                refreshWaypoints(system)
            }
            logger.info { "$system: ${waypoints.size} waypoints" }
            coroutineScope {
                waypoints.filter { it.hasTrait(WaypointTraitSymbol.MARKETPLACE) && !world.markets.containsKey(it.symbol) }.forEach { w ->
                    launch { fetchOrWarn("market ${w.symbol}") { refreshMarket(w.symbol) } }
                }
                waypoints.filter { it.hasTrait(WaypointTraitSymbol.SHIPYARD) && !world.shipyards.containsKey(it.symbol) }.forEach { w ->
                    launch { fetchOrWarn("shipyard ${w.symbol}") { refreshShipyard(w.symbol) } }
                }
            }
            val markets = world.markets.keys.count { OrbitalNames.getSectorSystem(it) == system }
            val shipyards = world.shipyards.keys.count { OrbitalNames.getSectorSystem(it) == system }
            logger.info { "$system loaded: ${waypoints.size} waypoints, $markets markets, $shipyards shipyards" }
            emit(Event.SystemLoaded(system, waypoints.size, markets, shipyards))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(e) { "Loading $system failed" }
            emit(Event.Failure("Loading $system failed: ${e.message}", e))
        }
    }

    private suspend fun fetchOrWarn(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiError) {
            logger.warn { "Skipping $what: ${e.message}" }
            emit(Event.Warning("Skipping $what: ${e.apiMessage}"))
        }
    }

    private suspend fun <T> onEngine(block: suspend () -> T): T = withContext(engineThread) { block() }

    private fun api(): SpaceTradersApi = api ?: error("Engine is not connected; boot first")

    private fun publish() {
        _state.value = world.snapshot(++version)
    }

    private fun emit(event: Event) {
        if (!_events.tryEmit(event)) logger.warn { "Event buffer full; dropped $event" }
    }
}

private fun Waypoint.hasTrait(trait: WaypointTraitSymbol): Boolean = traits.any { it.symbol == trait }

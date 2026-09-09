package engine

import api.ApiClient
import api.ApiError
import api.GameApi
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import behaviour.decisions.CreditPoint
import model.Agent
import model.Shipyard
import model.contract.Contract
import plan.Plan
import plan.RunLock
import model.actions.Survey
import model.market.Market
import model.market.MarketTransaction
import model.ship.Ship
import model.system.OrbitalNames
import model.system.System
import model.system.Waypoint
import storage.AgentStore
import storage.ExtractionRecord
import storage.SupplyRecord
import storage.Layout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Owns the game state. Bulk loads happen on one engine thread; ship verbs run on [scope] and
 * write the world through [ShipVerbs], one behaviour per ship, so they never race each other.
 * Readers take [state]; the UI and line mode never touch [world] directly.
 *
 * Ship automation lives in `plan.Supervisor`, which drives [verbs].
 */
class Engine(
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("engine")),
    val pacer: RequestPacer = RequestPacer(scope),
    val clock: GameClock = SystemClock,
    private val apiFactory: (token: String) -> GameApi = { token -> SpaceTradersApi(ApiClient(token, pacer)) },
    private val storeFactory: (agentSymbol: String, resetDate: String) -> AgentStore =
        { symbol, reset -> AgentStore.open(Layout.agentDir(symbol), symbol, reset) },
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "engine").apply { isDaemon = true } }
    private val engineThread = executor.asCoroutineDispatcher()

    val world = World()

    private val version = AtomicLong()
    private val _state = MutableStateFlow(world.snapshot(0))
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 1024)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    @Volatile
    var api: GameApi? = null
        private set

    @Volatile
    var store: AgentStore? = null
        private set

    @Volatile
    var verbs: Verbs? = null
        private set

    /** The HTTP client's counters, when the API is the real one. */
    val apiClient: ApiClient? get() = (api as? SpaceTradersApi)?.client

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
        (api as? SpaceTradersApi)?.client?.listener = { record -> store?.let { s -> scope.launch { s.logRequest(record) } } }

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
        loadActivity(store, agent.symbol)
        // Where the fleet has been this reset: home, wherever a ship stands, every system the plan holds, every jump landed.
        world.hqSystemSymbol()?.let { world.visitedSystems += it }
        world.ships.values.forEach { world.visitedSystems += it.nav.systemSymbol }
        world.plan?.systems?.keys?.let { world.visitedSystems += it }
        runCatching { world.visitedSystems += store.listJumpedSystems() }.onFailure { logger.warn(it) { "reading the systems jumped to failed" } }
        store.putCredits(clock.now(), agent.credits)
        publish()

        progress("Loading ships")
        refreshShips()

        verbs = ShipVerbs(api, world, clock, EngineSink())

        progress("Loading contracts")
        runCatching { api.listContracts() }.onSuccess { list ->
            list.forEach { world.contracts[it.id] = it }
            list.forEach { store.putContract(it, now = clock.now()) }
        }.onFailure { logger.warn(it) { "contracts not loaded" } }

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

    suspend fun refreshShips(): List<Ship> = onEngine {
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

    suspend fun refreshMarket(waypoint: String): Market {
        val market = api().getMarket(OrbitalNames.getSectorSystem(waypoint), waypoint)
        world.markets[market.symbol] = market
        store?.putMarket(market, clock.now())
        publish()
        emit(Event.MarketUpdated(market.symbol))
        return market
    }

    suspend fun refreshShipyard(waypoint: String): Shipyard {
        val shipyard = api().getShipyard(OrbitalNames.getSectorSystem(waypoint), waypoint)
        world.shipyards[shipyard.symbol] = shipyard
        store?.putShipyard(shipyard)
        publish()
        return shipyard
    }

    /** The verb layer; only available once booted. */
    fun verbs(): Verbs = verbs ?: error("Engine is not connected; boot first")

    /**
     * Keeps the world in step with the store while another process (line mode's `run`) does the
     * work: every [every], re-reads the agent, the fleet, the ships' phases, the credits history,
     * the recent transactions and the plan file, and publishes. Costs no requests. For the
     * dashboard, which watches rather than drives.
     */
    fun followStore(every: kotlin.time.Duration = 5.seconds): Job = scope.launch {
        while (isActive) {
            delay(every)
            val store = store ?: continue
            try {
                val agent = store.getAgent()
                if (agent != null) world.agent = agent
                store.listShips().forEach { world.ships[it.symbol] = it }
                loadActivity(store, world.agent?.symbol ?: continue)
                refreshConstructionBill()
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "following the store failed" }
            }
        }
    }

    private var billReadAt: java.time.Instant? = null

    /** The dashboard drives no ships, so it reads the home site's bill itself, every five minutes, for the progress panel. */
    private suspend fun refreshConstructionBill() {
        val hq = world.hqSystemSymbol() ?: return
        val site = world.waypoints.values.firstOrNull { it.systemSymbol == hq && it.isUnderConstruction } ?: return
        val now = clock.now()
        if (billReadAt?.let { java.time.Duration.between(it, now).toMinutes() < 5 } == true) return
        billReadAt = now
        runCatching { verbs().construction(site.symbol) }.onFailure { logger.warn(it) { "could not read ${site.symbol}" } }
    }

    /** Phases, credits history, recent transactions and the plan, from the store and the plan file. */
    private suspend fun loadActivity(store: AgentStore, agentSymbol: String) {
        val since = clock.now().minus(java.time.Duration.ofHours(2))
        store.listCheckpoints().forEach { c ->
            world.shipStatus[c.id] = ShipStatus(c.behaviour, c.phase, c.detail, c.updatedAt)
        }
        // The bank's history goes back further than the transactions: the chart shows the whole afternoon.
        world.creditsHistory = store.listCredits(clock.now().minus(java.time.Duration.ofHours(6)))
        world.recentTransactions = store.listTransactions(since)
        world.taggedTransactions = store.listTaggedTransactions()
        world.ledger = store.listLedger()
        world.phases = store.listPhases(clock.now().minus(java.time.Duration.ofHours(24)))
        world.activities = store.listActivities(clock.now().minus(java.time.Duration.ofHours(24)))
        world.extractions = store.listExtractions().takeLast(2000)
        world.plan = runCatching { Plan.load(Layout.planFile(agentSymbol)) }.getOrNull()
        world.runner = RunLock.read(Layout.runLockFile(agentSymbol))
    }

    fun shutdown() {
        scope.cancel()
        runCatching { apiClient?.close() }
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
                waypoints.filter { it.hasMarket && !world.markets.containsKey(it.symbol) }.forEach { w ->
                    launch { fetchOrWarn("market ${w.symbol}") { refreshMarket(w.symbol) } }
                }
                waypoints.filter { it.hasShipyard && !world.shipyards.containsKey(it.symbol) }.forEach { w ->
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

    private fun api(): GameApi = api ?: error("Engine is not connected; boot first")

    internal fun publish() {
        _state.value = world.snapshot(version.incrementAndGet())
    }

    internal fun emit(event: Event) {
        if (!_events.tryEmit(event)) logger.warn { "Event buffer full; dropped $event" }
    }

    /** Where the verbs report what they changed: the store gets it, the snapshot and events follow. */
    private inner class EngineSink : VerbSink {
        override suspend fun shipChanged(ship: Ship) {
            store?.putShip(ship)
            publish()
        }

        override suspend fun agentChanged(agent: Agent) {
            store?.putAgent(agent)
            val now = clock.now()
            store?.putCredits(now, agent.credits)
            world.creditsHistory = (world.creditsHistory + CreditPoint(now, agent.credits)).filter { it.at.isAfter(now.minus(java.time.Duration.ofHours(2))) }
            publish()
        }

        override suspend fun marketChanged(market: Market) {
            store?.putMarket(market, clock.now())
            publish()
            emit(Event.MarketUpdated(market.symbol))
        }

        override suspend fun shipyardChanged(shipyard: Shipyard) {
            store?.putShipyard(shipyard)
            publish()
        }

        override suspend fun waypointChanged(waypoint: Waypoint) {
            store?.putWaypoints(listOf(waypoint))
            publish()
        }

        override suspend fun surveysAdded(surveys: List<Survey>) = publish()

        override suspend fun transaction(transaction: MarketTransaction, chain: String?) {
            store?.putTransaction(transaction, chain)
            val cutoff = clock.now().minus(java.time.Duration.ofHours(2))
            world.recentTransactions = (world.recentTransactions + transaction).filter { java.time.Instant.parse(it.timestamp).isAfter(cutoff) }
            world.taggedTransactions = world.taggedTransactions + storage.TaggedTransaction(transaction, chain)
        }

        private suspend fun ledger(ship: String, kind: String, credits: Long, note: String) {
            val entry = storage.LedgerEntry(clock.now(), ship, kind, credits, note)
            store?.putLedger(entry)
            world.ledger = world.ledger + entry
        }

        override suspend fun extraction(record: ExtractionRecord) {
            store?.putExtraction(record)
            world.extractions = (world.extractions + record).takeLast(2000)
        }

        override suspend fun gateRead(gate: model.responsebody.JumpGate) { store?.putGate(gate) }

        override suspend fun systemLoaded(system: System, waypoints: List<Waypoint>) {
            store?.putSystem(system)
            store?.putWaypoints(waypoints)
            publish()
            emit(Event.SystemLoaded(system.symbol, waypoints.size, 0, 0))
        }

        override suspend fun supplied(record: SupplyRecord) {
            store?.putSupply(record)
        }

        override suspend fun contractChanged(contract: Contract, cost: Long, accepted: Boolean, fulfilled: Boolean) {
            val now = clock.now()
            store?.putContract(contract, cost, acceptedAt = if (accepted) now else null, fulfilledAt = if (fulfilled) now else null, now = now)
            if (accepted) ledger("", "contract", contract.terms.payment.onAccepted, "accepted ${contract.id.takeLast(6)}")
            if (fulfilled) ledger("", "contract", contract.terms.payment.onFulfilled, "fulfilled ${contract.id.takeLast(6)}")
            publish()
        }

        private val lastPhase = java.util.concurrent.ConcurrentHashMap<String, String>()

        override suspend fun statusChanged(ship: String, status: ShipStatus?, params: String) {
            if (status == null) {
                store?.deleteCheckpoint(ship)
                lastPhase.remove(ship)
            } else {
                store?.putCheckpoint(ship, status.behaviour, ship, status.phase, params, status.detail, status.since)
                // One row per phase change, not per status line, so the idle report reads the ship's day.
                val key = "${status.behaviour}/${status.phase}"
                if (lastPhase.put(ship, key) != key) store?.putPhase(storage.PhaseRecord(clock.now(), ship, status.behaviour, status.phase, status.detail))
            }
            publish()
        }

        override fun event(event: Event) {
            when (event) {
                is Event.ShipPurchased -> scope.launch { ledger(event.ship, "ships", -event.credits, event.type) }
                is Event.Charted -> scope.launch { ledger(event.ship, "chart", event.credits, event.waypoint) }
                is Event.Activity -> scope.launch {
                    val record = storage.ActivityRecord(clock.now(), event.ship, event.behaviour, event.kind, event.detail, event.seconds)
                    store?.putActivity(record)
                    world.activities = world.activities + record
                }
                is Event.Jumped -> {
                    // The first jump of ours into a system is worth a line: the fleet's reach grew.
                    val system = model.system.OrbitalNames.getSectorSystem(event.waypoint)
                    if (world.visitedSystems.add(system)) event(Event.Notable("system", "${event.ship} is the first of ours into $system, through ${event.waypoint}"))
                }
                is Event.Notable -> scope.launch { store?.putNotable(clock.now(), event.kind, event.text) }
                else -> {}
            }
            emit(event)
        }
    }
}

package model

import api.ApiClient
import api.ApiError
import api.RequestPacer
import api.SpaceTradersApi
import client.SpaceTradersClient
import data.AGENT_TOKEN_FILE
import data.DbClient
import data.FileWritingQueue
import data.SavedScripts
import data.readSecret
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import model.GameState.GAME_API
import model.GameState.shipsToScripts
import model.contract.Contract
import model.exceptions.ProfileLoadingFailure
import model.market.Market
import model.responsebody.RegisterResponse
import model.ship.Ship
import model.ship.ShipRole
import model.system.OrbitalNames
import model.system.System
import model.system.Waypoint
import notification.Notification
import notification.NotificationManager
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import screen.ColorPalette
import screen.TextAnimationContainer
import script.ScriptExecutor
import script.repo.CommandShipStartScript
import script.repo.pricing.PriceDiscoveryScript
import script.repo.pricing.PriceFetcherScript
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

const val DEFAULT_PROF_DIR = "profile"
const val DEFAULT_PROF_FILE = "$DEFAULT_PROF_DIR/profile.settings.json"

private val logger = KotlinLogging.logger {}

object GameState {

    const val GAME_API = "https://api.spacetraders.io/v2/"

    /** Everything that talks to the API or mutates state off the UI thread runs here. */
    val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("engine"))

    /** One pacer per process: the rate limit is per account, so every agent shares it. */
    val pacer: RequestPacer by lazy { RequestPacer(engineScope) }

    lateinit var api: SpaceTradersApi
        private set

    // we can only ever have one contract for now
    var contract: Contract? = null
    var commandShip: Ship? = null

    lateinit var profData: ProfileData
    lateinit var agent: Agent

    // Read on the render thread while the engine fills them in, hence concurrent maps.
    val systems: MutableMap<String, System> = ConcurrentHashMap()
    val waypoints: MutableMap<String, Waypoint> = ConcurrentHashMap()
    val shipyards: MutableMap<String, Shipyard> = ConcurrentHashMap()
    val ships: MutableMap<String, Ship> = ConcurrentHashMap()
    val markets: MutableMap<String, Market> = ConcurrentHashMap()
    val marketsBySystem = mutableMapOf<String, MutableList<Market>>()
    val shipyardsBySystem = mutableMapOf<String, MutableList<Shipyard>>()

    val shipsToScripts: MutableMap<Ship, ScriptExecutor<*>> = mutableMapOf()
    val scriptsRunning = mutableListOf<ScriptExecutor<*>>()
    var engineeredAsteroid: String = ""
    private var awaitingScripts = mutableListOf<ScriptExecutor<*>>()

    /** Completes once the home system's waypoints, markets and shipyards have been fetched. */
    private val systemLoaded = CompletableDeferred<Unit>()

    /**
     * Master switch for ship automation. Off pending the scripting overhaul (see
     * docs/codebase-review-2026-09.md, sections 4.4 and 7). While it is off, START and NEW only
     * load the agent, fleet and system data so the dashboard can be exercised against the live API;
     * no script is constructed, resumed or run.
     */
    var scriptsEnabled = false

    fun initializeGameState(profileDataFile: String = DEFAULT_PROF_FILE) {
        logger.info { "Booting GameState from existing Agent" }
        profData = ApiJson.decodeFromString<ProfileData>(File(profileDataFile).readText())
        Profile.createProfile(profData)
        val token = readSecret(AGENT_TOKEN_FILE) ?: throw ProfileLoadingFailure(
            "No agent token at $AGENT_TOKEN_FILE. Type NEW to register an agent, or paste an existing agent token into that file."
        )
        connectApi(token)
        initializeDataManagers()

        agent = try {
            runBlocking { api.getMyAgent() }
        } catch (e: ApiError) {
            throw ProfileLoadingFailure(
                "The API rejected the token in $AGENT_TOKEN_FILE: ${e.apiMessage} (HTTP ${e.status}, code ${e.code}). " +
                        "If the server has reset since it was issued, mint a new token or type NEW."
            )
        }
        // The token decides which agent this is; the profile name follows it.
        if (!profData.name.equals(agent.symbol, ignoreCase = true)) {
            logger.info { "Profile name '${profData.name}' does not match the token's agent '${agent.symbol}'; updating the profile" }
            profData.name = agent.symbol
            saveProfile(profileDataFile, profData)
        }
        postInitGameLoading()
        loadScripts()
    }

    suspend fun bootGameStateFromNewAgent(profileData: ProfileData, registerResponse: RegisterResponse) {
        logger.info { "Booting GameState from newly created Agent" }

        profData = profileData
        Profile.createProfile(profileData)
        connectApi(registerResponse.token)
        initializeDataManagers()

        registerResponse.token = "" // clear auth token from our memory
        agent = registerResponse.agent
        contract = registerResponse.contract
        registerResponse.ships.forEach { ships[it.symbol] = it }
        commandShip = registerResponse.ships.firstOrNull { it.registration.role == ShipRole.COMMAND }
            ?: registerResponse.ships.firstOrNull()
            ?: throw ProfileLoadingFailure("Registration returned no ships")
        postInitGameLoading()

        if (!scriptsEnabled) {
            logger.info { "Scripts are disabled; skipping the startup automation" }
            return
        }

        engineScope.launch {
            systemLoaded.await()
            // Give us a little more time, to avoid race conditions
            delay(2_000)
            logger.info { "System loaded; starting ${awaitingScripts.size} scripts" }
            awaitingScripts.forEach { s -> s.execute() }
        }

        // basic strategy
        awaitingScripts.add(CommandShipStartScript(commandShip!!))

        // Registration currently includes a probe; put it to work fetching prices
        ships.values.firstOrNull { it.registration.role == ShipRole.SATELLITE }?.let { PriceFetcherScript(it).execute() }
        PriceDiscoveryScript(getHqSystem().symbol)
    }

    /** Stops the engine and closes the HTTP clients. Safe to call before anything was started. */
    fun shutdown() {
        engineScope.cancel()
        if (::api.isInitialized) api.client.close()
        SpaceTradersClient.closeIfOpen()
    }

    private fun connectApi(token: String) {
        api = SpaceTradersApi(ApiClient(token, pacer))
        // The parked script layer still talks through the old client; keep it usable but idle.
        SpaceTradersClient.createClient(token)
        if (scriptsEnabled) SpaceTradersClient.beginPollingRequests()
    }

    private fun initializeDataManagers() {
        DbClient.createClient()
        FileWritingQueue.createFileWritingQueue()
    }

    private fun postInitGameLoading() {
        NotificationManager.notifications.add(
            Notification(
                "Welcome, Magnate", Instant.now(),
                TextAnimationContainer.newNotification!!, ColorPalette.secondaryInfoBlue,
                "Welcome to the command console"
            )
        )
        logger.info { "Agent ${agent.symbol}, headquarters ${agent.headquarters}" }
        writeCache("agent", agent.symbol, agent)
        loadAllData()

        val hq = OrbitalNames.getSectorSystem(agent.headquarters)
        refreshSystem(hq)
        if (waypoints.values.none { it.systemSymbol == hq }) {
            engineScope.launch { loadSystemContents(hq) }
        } else {
            logger.info { "Using cached waypoints, markets and shipyards for $hq" }
            systemLoaded.complete(Unit)
        }
    }

    private fun refreshSystem(systemSymbol: String) {
        logger.info { "Ensuring home system $systemSymbol is loaded" }
        val system = try {
            runBlocking { api.getSystem(systemSymbol) }
        } catch (e: ApiError) {
            throw ProfileLoadingFailure("Could not load home system $systemSymbol: ${e.apiMessage} (HTTP ${e.status}, code ${e.code})")
        }
        systems[systemSymbol] = system
        writeCache("systems", systemSymbol, system)
    }

    /**
     * Fetches every waypoint of [system] (twenty per request), then the market and shipyard of each
     * waypoint that has one. Runs on the engine scope; the dashboard fills in as results land.
     */
    private suspend fun loadSystemContents(system: String) {
        try {
            val loaded = api.listSystemWaypoints(system)
            loaded.forEach { w ->
                waypoints[w.symbol] = w
                writeCache("waypoints", w.symbol, w)
            }
            logger.info { "Loaded ${loaded.size} waypoints in $system" }

            coroutineScope {
                loaded.filter { it.hasTrait(WaypointTraitSymbol.MARKETPLACE) }.forEach { w ->
                    launch { fetchOrSkip("market ${w.symbol}") { storeMarket(api.getMarket(system, w.symbol)) } }
                }
                loaded.filter { it.hasTrait(WaypointTraitSymbol.SHIPYARD) }.forEach { w ->
                    launch { fetchOrSkip("shipyard ${w.symbol}") { storeShipyard(api.getShipyard(system, w.symbol)) } }
                }
            }
            logger.info { "System $system loaded: ${markets.size} markets, ${shipyards.size} shipyards" }
            NotificationManager.createNotification(
                "$system loaded", "${loaded.size} waypoints, ${markets.size} markets, ${shipyards.size} shipyards"
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            NotificationManager.exceptNotification("Loading $system failed", e.message ?: e::class.simpleName ?: "", e)
        } finally {
            systemLoaded.complete(Unit)
        }
    }

    private suspend fun fetchOrSkip(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiError) {
            logger.warn { "Skipping $what: ${e.message}" }
            NotificationManager.errorNotification("Skipping $what", e.apiMessage)
        }
    }

    private fun storeMarket(market: Market) {
        markets[market.symbol] = market
        synchronized(marketsBySystem) {
            marketsBySystem.getOrPut(OrbitalNames.getSectorSystem(market.symbol)) { mutableListOf() }.add(market)
        }
        writeCache("markets", market.symbol, market)
    }

    private fun storeShipyard(shipyard: Shipyard) {
        shipyards[shipyard.symbol] = shipyard
        synchronized(shipyardsBySystem) {
            shipyardsBySystem.getOrPut(OrbitalNames.getSectorSystem(shipyard.symbol)) { mutableListOf() }.add(shipyard)
        }
        writeCache("shipyards", shipyard.symbol, shipyard)
    }

    private fun loadScripts() {
        fetchAllShips()
        if (scriptsEnabled) resumeSavedScripts() else logger.info { "Scripts are disabled; not resuming saved scripts" }
    }

    private fun fetchAllShips() {
        val fleet = try {
            runBlocking { api.listMyShips() }
        } catch (e: ApiError) {
            logger.error { "Could not list ships: ${e.message}" }
            NotificationManager.errorNotification("Could not list ships", e.apiMessage)
            emptyList()
        }
        fleet.forEach { ships[it.symbol] = it }
        logger.info { "Loaded ${ships.size} ships" }
    }

    private fun resumeSavedScripts() {
        transaction {
            SavedScripts.selectAll().where { SavedScripts.entityId like "${agent.symbol}%" }.forEach { s ->
                val ship = ships[s[SavedScripts.entityId]] ?: return@forEach
                val shipScript = when (s[SavedScripts.scriptType]) {
                    "BasicMiningScript" -> {
                        null
                    }

                    "PriceFetcherScript" -> {
                        val script = PriceFetcherScript(
                            ship, PriceFetcherScript.PriceFetcherState.valueOf(s[SavedScripts.scriptState])
                        )
                        script.uuid = s[SavedScripts.id]
                        script
                    }

                    "BasicHaulerScript" -> {
                        null
                    }

                    "TradingHaulerScript" -> {
                        null
                    }

                    else -> {
                        null
                    }
                }
                if (shipScript != null) {
                    shipScript.execute()
                    shipsToScripts[ship] = shipScript
                    scriptsRunning.add(shipScript)
                }
            }
        }

        transaction {
            SavedScripts.selectAll().where { SavedScripts.entityId notInList ships.keys }.forEach { s ->
                val managerScript = when (s[SavedScripts.scriptType]) {
                    "PriceDiscoveryScript" -> {
                        val script = PriceDiscoveryScript(
                            s[SavedScripts.entityId]!!,
                            PriceDiscoveryScript.PriceDiscoveryState.valueOf(s[SavedScripts.scriptState])
                        )
                        script.uuid = s[SavedScripts.id]
                        script
                    }

                    else -> null
                }
                if (managerScript != null) {
                    managerScript.execute()
                    scriptsRunning.add(managerScript as ScriptExecutor<*>)
                }
            }
        }
    }

    private fun loadAllData() {
        logger.info { "Loading data from files" }
        systems.putAll(loadDataFromJsonFile<System>("systems"))
        shipyards.putAll(loadDataFromJsonFile<Shipyard>("shipyards"))
        waypoints.putAll(loadDataFromJsonFile<Waypoint>("waypoints"))
        markets.putAll(loadDataFromJsonFile<Market>("markets"))
        markets.values.forEach { m ->
            marketsBySystem.getOrPut(OrbitalNames.getSectorSystem(m.symbol)) { mutableListOf() }.add(m)
        }
        logger.info { "Done loading data from files: ${systems.size} systems, ${waypoints.size} waypoints, ${markets.size} markets, ${shipyards.size} shipyards" }
    }

    /** Reads one cached entity per file; a file an older model wrote that no longer decodes is skipped with a warning. */
    private inline fun <reified T> loadDataFromJsonFile(folderRoot: String): Map<String, T> =
        File("$DEFAULT_PROF_DIR/$folderRoot")
            .walk()
            .filter { f -> f.isFile && f.canRead() }
            .mapNotNull { f ->
                runCatching { f.nameWithoutExtension.uppercase() to ApiJson.decodeFromString<T>(f.readText()) }
                    .onFailure { logger.warn { "Skipping cache file ${f.path}: ${it.message}" } }
                    .getOrNull()
            }
            .toMap()

    private inline fun <reified T> writeCache(folder: String, name: String, value: T) {
        File("$DEFAULT_PROF_DIR/$folder/$name").writeText(ApiJson.encodeToString(value))
    }

    fun getHqSystem(): System = systems[OrbitalNames.getSectorSystem(agent.headquarters)]!!
}

fun api(params: String): String = "$GAME_API$params"

fun getScriptForShip(ship: Ship): ScriptExecutor<*>? = shipsToScripts[ship]

private fun Waypoint.hasTrait(trait: WaypointTraitSymbol): Boolean = traits.any { it.symbol == trait }

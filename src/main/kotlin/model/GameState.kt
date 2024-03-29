package model

import Symbol
import client.SpaceTradersClient
import client.SpaceTradersClient.callGet
import client.SpaceTradersClient.ignoredFailback
import data.DbClient
import data.FileWritingQueue
import data.SavedScripts
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.GameState.GAME_API
import model.GameState.shipsToScripts
import model.contract.Contract
import model.exceptions.ProfileLoadingFailure
import model.market.Market
import model.responsebody.RegisterResponse
import model.ship.Ship
import model.ship.listShips
import model.system.OrbitalNames
import model.system.System
import model.system.SystemWaypoint
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
import java.lang.Thread.sleep
import java.time.Instant
import kotlin.concurrent.timer
import kotlin.reflect.KSuspendFunction1

const val DEFAULT_PROF_DIR = "profile"
const val DEFAULT_PROF_FILE = "$DEFAULT_PROF_DIR/profile.settings.json"

private val logger = KotlinLogging.logger {}
object GameState {

    const val GAME_API = "https://api.spacetraders.io/v2/"

    val stateDispatcher = Dispatchers.Default

    // we can only ever have one contract for now
    var contract: Contract? = null
    var commandShip: Ship? = null

    lateinit var profData: ProfileData
    lateinit var agent: Agent
    lateinit var systems: MutableMap<String, System>
    lateinit var waypoints: MutableMap<String, Waypoint>
    lateinit var shipyards: MutableMap<String, Shipyard>
    var ships = mutableMapOf<String, Ship>()
    val shipsToScripts: MutableMap<Ship, ScriptExecutor<*>> = mutableMapOf()
    lateinit var markets: MutableMap<String, Market>
    val marketsBySystem = mutableMapOf<String, MutableList<Market>>()
    val shipyardsBySystem = mutableMapOf<String, MutableList<Shipyard>>()
    val scriptsRunning = mutableListOf<ScriptExecutor<*>>()
    var engineeredAsteroid: String = ""
    private var initObjRequests = 0
    private var initObjRequestsFilled = 0
    private var awaitingScripts = mutableListOf<ScriptExecutor<*>>()

    fun initializeGameState(profileDataFile: String = DEFAULT_PROF_FILE) {
        logger.info { "Booting GameState from existing Agent" }
        profData = Json.decodeFromString<ProfileData>(File(profileDataFile).readText())
        Profile.createProfile(profData)
        SpaceTradersClient.createClient(File("$DEFAULT_PROF_DIR/authtoken.secret"))
        initializeDataManagers()

        SpaceTradersClient.beginPollingRequests()
        agent = (getAgentData() ?: failedToLoad("Agent")) as Agent
        postInitGameLoading()
        loadScripts()
    }

    suspend fun bootGameStateFromNewAgent(profileData: ProfileData, registerResponse: RegisterResponse) {
        logger.info { "Booting GameState from newly created Agent" }

        profData = profileData
        Profile.createProfile(profileData)
        SpaceTradersClient.createClient(registerResponse.token)
        initializeDataManagers()

        registerResponse.token = "" // clear auth token from our memory
        SpaceTradersClient.beginPollingRequests()
        agent = registerResponse.agent
        contract = registerResponse.contract
        commandShip = registerResponse.ship
        postInitGameLoading()

        timer("initialLoading", true, 0, 100) {
            if (initObjRequestsFilled == initObjRequests) {
                this.cancel()

                // Give us a little more time, to avoid race conditions
                sleep(2_000)
                logger.info { "Finished loading at $initObjRequestsFilled/$initObjRequests" }
                awaitingScripts.forEach { s -> s.execute() }
            }
        }

        // basic strategy
        awaitingScripts.add(CommandShipStartScript(commandShip!!))

        // ships are 1-indexed
        ships["agent.symbol${-2}"]?.let { PriceFetcherScript(it).execute() }
        PriceDiscoveryScript(getHqSystem().symbol)
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
        logger.info {
            "Name ${profData.name}"
            "HQ ${agent.headquarters}"
        }
        File("$DEFAULT_PROF_DIR/agent/${profData.name}").writeText(Json.encodeToString(agent))
        loadAllData()
        refreshSystem(OrbitalNames.getSectorSystem(agent.headquarters)) ?: failedToLoad("Headquarters")
        if (waypoints.isEmpty()) {
            refreshWaypoints(getHqSystem().symbol, getHqSystem().waypoints)
        }
    }

    private inline fun <reified T> fetchSystemsForWaypointsWithTraits(
        systemSymbol: String, traitType: WaypointTraitSymbol,
        endpoint: String, callback: KSuspendFunction1<T, Unit>
    ) {
        waypoints.values
            .filter { w ->
                w.systemSymbol == systemSymbol && w.traits.find { t -> t.symbol == traitType } != null
            }
            .forEach { w ->
                SpaceTradersClient.enqueueRequest<T>(callback, ::ignoredFailback, request {
                    url(api("systems/$systemSymbol/waypoints/${w.symbol}/$endpoint"))
                })
            }
    }

    private fun loadScripts() {
        fetchAllShips()
    }

    private fun refreshShipyards() = fetchSystemsForWaypointsWithTraits(
        getHqSystem().symbol, WaypointTraitSymbol.SHIPYARD,
        "shipyard", ::shipyardCb
    )

    private fun refreshMarkets() = fetchSystemsForWaypointsWithTraits(
        getHqSystem().symbol, WaypointTraitSymbol.MARKETPLACE,
        "market", ::marketCb
    )

    private fun refreshWaypoints(systemSymbol: String, waypoints: List<SystemWaypoint>) {
        logger.info { "Loading waypoints of $systemSymbol for ${waypoints.size} waypoints" }
        initObjRequests += waypoints.size
        waypoints.forEach { w ->
            SpaceTradersClient.enqueueRequest(::waypointCb, ::ignoredFailback, request {
                url(api("systems/$systemSymbol/waypoints/${w.symbol}"))
            })
        }
    }

    private suspend fun shipyardCb(shipyardResults: Shipyard) {
        val system = OrbitalNames.getSectorSystem(shipyardResults.symbol)
        shipyards[shipyardResults.symbol] = shipyardResults
        shipyardsBySystem.getOrPut(system) { mutableListOf() }.add(shipyardResults)
        File("$DEFAULT_PROF_DIR/shipyards/${shipyardResults.symbol}")
            .writeText(Json.encodeToString(shipyardResults))
        initObjRequestsFilled++
    }

    private suspend fun marketCb(market: Market) {
        val system = OrbitalNames.getSectorSystem(market.symbol)
        markets[market.symbol] = market
        marketsBySystem.getOrPut(system) { mutableListOf() }.add(market)
        File("$DEFAULT_PROF_DIR/markets/${market.symbol}")
            .writeText(Json.encodeToString(market))
        initObjRequestsFilled++
    }

    private suspend fun waypointCb(waypoint: Waypoint) {
        waypoints[waypoint.symbol] = waypoint
        File("$DEFAULT_PROF_DIR/waypoints/${waypoint.symbol}")
            .writeText(Json.encodeToString(waypoint))
        if (waypoint.traits.any { wt -> wt.symbol == WaypointTraitSymbol.MARKETPLACE }) {
            initObjRequests++
            fetchWaypointByType(waypoint.systemSymbol, waypoint.symbol, "market", ::marketCb)
        }

        if (waypoint.traits.any { wt -> wt.symbol == WaypointTraitSymbol.SHIPYARD }) {
            initObjRequests++
            fetchWaypointByType(waypoint.systemSymbol, waypoint.symbol, "shipyard", ::shipyardCb)
        }
        initObjRequestsFilled++
    }

    private inline fun <reified T> fetchWaypointByType(
        systemSymbol: String, waypointSymbol: String,
        endpoint: String, callback: KSuspendFunction1<T, Unit>
    ) {
        SpaceTradersClient.enqueueRequest<T>(callback, ::ignoredFailback, request {
            url(api("systems/$systemSymbol/waypoints/$waypointSymbol/$endpoint"))
        })
    }

    private fun loadAllData() {
        logger.info { "Loading data from files" }
        systems = loadDataFromJsonFile<System>("systems")
        shipyards = loadDataFromJsonFile<Shipyard>("shipyards")
        waypoints = loadDataFromJsonFile<Waypoint>("waypoints")
        markets = loadDataFromJsonFile<Market>("markets")
        markets.values.forEach { m ->
            marketsBySystem.getOrPut(OrbitalNames.getSectorSystem(m.symbol)) {
                mutableListOf()
            }.add(m)
        }
        logger.info { "Done loading data from files" }
    }

    private fun fetchAllShips() {
        val shipList = listShips()
        ships.putAll(convertToMap(shipList))

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

    private fun <T> convertToMap(list: List<T>?): MutableMap<String, T> where T : Symbol {
        if (list != null) {
            return list.associateBy(
                keySelector = { it.symbol },
                valueTransform = { it }
            ).toMutableMap()
        }

        return mutableMapOf()
    }


    private inline fun <reified T> loadDataFromJsonFile(folderRoot: String): MutableMap<String, T> =
        File("$DEFAULT_PROF_DIR/$folderRoot")
            .walk()
            .filter { f -> f.isFile && f.canRead() }
            .associateBy(
                keySelector = { it.nameWithoutExtension.uppercase() },
                valueTransform = { Json.decodeFromString<T>(it.readText()) }
            )
            .toMutableMap()

    fun getHqSystem(): System = systems[OrbitalNames.getSectorSystem(agent.headquarters)]!!

    private fun failedToLoad(what: String) {
        throw ProfileLoadingFailure("Failed to load '$what' data.")
    }

    private fun getAgentData(): Agent? = callGet<Agent>(request {
        url(api("my/agent"))
    })

    private fun refreshSystem(systemName: String): System? {
        logger.info { "Ensuring home system $systemName is loaded" }
        val system = callGet<System>(request {
            url(api("systems/$systemName"))
        })

        if (system != null) {
            systems[systemName] = system
            saveSystem(systemName)
        }

        return system
    }

    private fun saveSystem(systemName: String) {
        File("$DEFAULT_PROF_DIR/systems/$systemName").writeText(Json.encodeToString(systems[systemName]))
    }
}

fun api(params: String): String = "$GAME_API/$params"

fun getScriptForShip(ship: Ship): ScriptExecutor<*>? = shipsToScripts[ship]
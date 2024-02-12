package model

import Symbol
import client.SpaceTradersClient
import client.SpaceTradersClient.ignoredCallback
import client.SpaceTradersClient.ignoredFailback
import data.DbClient
import data.SavedScripts
import model.system.System
import model.system.OrbitalNames
import io.ktor.client.request.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.GameState.GAME_API
import model.GameState.shipsToScripts
import model.contract.Contract
import model.exceptions.ProfileLoadingFailure
import model.market.Market
import model.ship.Ship
import model.ship.listShips
import model.system.SystemWaypoint
import model.system.Waypoint
import org.jetbrains.exposed.sql.selectAll
import responsebody.RegisterResponse
import script.MessageableScriptExecutor
import script.ScriptExecutor
import script.repo.BasicHaulerScript
import script.repo.BasicMiningScript
import java.io.File

const val DEFAULT_PROF_DIR = "profile"
const val DEFAULT_PROF_FILE = "$DEFAULT_PROF_DIR/profile.settings.json"
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
    lateinit var shipyards: MutableMap<String, ShipyardResults>
    lateinit var ships: MutableMap<String, Ship>
    val shipsToScripts: MutableMap<Ship, MessageableScriptExecutor<*, *>> = mutableMapOf()
    lateinit var markets: MutableMap<String, Market>

    fun initializeGameState(profileDataFile: String = DEFAULT_PROF_FILE) {
        profData = Json.decodeFromString<ProfileData>(File(profileDataFile).readText())
        Profile.createProfile(profData)
        SpaceTradersClient.createClient(File("$DEFAULT_PROF_DIR/authtoken.secret"))
        SpaceTradersClient.beginPollingRequests()
        agent = (getAgentData() ?: failedToLoad("Agent")) as Agent
        postInitGameLoading()
    }

    fun bootGameStateFromNewAgent(profileData: ProfileData, registerResponse: RegisterResponse) {
        profData = profileData
        Profile.createProfile(profileData)
        SpaceTradersClient.createClient(registerResponse.token)
        registerResponse.token = "" // clear auth token from our memory
        SpaceTradersClient.beginPollingRequests()
        agent = registerResponse.agent
        contract = registerResponse.contract
        commandShip = registerResponse.ship
        postInitGameLoading()
    }

    private fun postInitGameLoading() {
        println("Name ${profData.name}")
        println("HQ @ ${agent.headquarters}")
        loadAllData()
        refreshSystem(OrbitalNames.getSectorSystem(agent.headquarters)) ?: failedToLoad("Headquarters")
        if (waypoints.isEmpty()) {
            refreshWaypoints(getHqSystem().symbol, getHqSystem().waypoints)
        }
        if (markets.isEmpty()) {
            refreshMarkets()
        }
    }

    private fun refreshMarkets() {
        waypoints.values
            .filter { w -> w.traits.find { t -> t.symbol == TraitTypes.MARKETPLACE } != null}
            .forEach { w ->
                SpaceTradersClient.enqueueRequest<Market>(::marketCb, ::ignoredFailback, request {
                    url(api("systems/${getHqSystem().symbol}/waypoints/${w.symbol}/market"))
                })
            }
    }

    private fun refreshWaypoints(systemSymbol: String, waypoints: List<SystemWaypoint>) {
        waypoints.forEach { w ->
            SpaceTradersClient.enqueueRequest(::waypointCb, ::ignoredFailback, request {
                url(api("systems/$systemSymbol/waypoints/${w.symbol}"))
            })
        }
    }

    suspend fun marketCb(market: Market) {
        markets[market.symbol] = market
        File("$DEFAULT_PROF_DIR/markets/${market.symbol}")
            .writeText(Json.encodeToString(market))
    }

    suspend private fun waypointCb(waypoint: Waypoint) {
        waypoints[waypoint.symbol] = waypoint
        File("$DEFAULT_PROF_DIR/waypoints/${waypoint.symbol}")
            .writeText(Json.encodeToString(waypoint))
    }

    private fun loadAllData() {
        systems = loadDataFromJsonFile<System>("systems")
        shipyards = loadDataFromJsonFile<ShipyardResults>("shipyards")
        ships = loadDataFromJsonFile<Ship>("ships")
        waypoints = loadDataFromJsonFile<Waypoint>("waypoints")
        markets = loadDataFromJsonFile<Market>("markets")
    }

    fun fetchAllShips() {
        val shipList = listShips()
        // pull from DB to get saved state
        shipList?.forEach { s ->
            when(s.registration.role) {
                "EXCAVATOR" -> {
                    val script = BasicMiningScript(s)
                    script.execute()
                }
                "TRANSPORT" -> {
                    val script = BasicHaulerScript(s)
                    script.execute()
                }
            }
        }
        ships = convertToMap(shipList)
    }

    private fun <T> convertToMap(list: List<T>?): MutableMap<String, T> where T : Symbol {
        if (list != null) {
            return list.associateBy(
                keySelector = {it.symbol},
                valueTransform = {it}
            ).toMutableMap()
        }

        return mutableMapOf()
    }


    private inline fun <reified T> loadDataFromJsonFile(folderRoot: String): MutableMap<String, T> =
        File("$DEFAULT_PROF_DIR/$folderRoot")
            .walk()
            .filter { f -> f.isFile && f.canRead() }
            .associateBy(
                keySelector = {it.nameWithoutExtension.uppercase()},
                valueTransform = {Json.decodeFromString<T>(it.readText())}
            )
            .toMutableMap()

    fun getHqSystem(): System = systems[OrbitalNames.getSectorSystem(agent.headquarters)]!!

    private fun failedToLoad(what: String) {
        throw ProfileLoadingFailure("Failed to load '$what' data.")
    }

    private fun getAgentData(): Agent? = SpaceTradersClient.callGet<Agent>(request {
        url(api("my/agent"))
    })

    fun refreshSystem(systemName: String): System? {
        println("Loading ${systemName}")
        val system = SpaceTradersClient.callGet<System>(request {
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

fun getScriptForShip(ship: Ship): MessageableScriptExecutor<*, *>? = shipsToScripts[ship]
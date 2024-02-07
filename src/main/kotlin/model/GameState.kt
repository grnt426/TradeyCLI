package model

import client.SpaceTradersClient
import model.system.System
import model.system.OrbitalNames
import io.ktor.client.request.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import model.GameState.GameApi
import model.exceptions.ProfileLoadingFailure
import model.system.Waypoint
import java.io.File

const val DEFAULT_PROF_DIR = "profile"
const val DEFAULT_PROF_FILE = "$DEFAULT_PROF_DIR/profile.settings.json"
object GameState {

    const val GameApi = "https://api.spacetraders.io/v2/"

    lateinit var profData: ProfileData
    lateinit var agent: Agent
    lateinit var systems: MutableMap<String, System>
    lateinit var waypoints: MutableMap<String, Waypoint>
    lateinit var shipyards: MutableMap<String, ShipyardResults>

    fun initializeGameState(profileDataFile: String = DEFAULT_PROF_FILE): GameState {
        profData = Json.decodeFromString<ProfileData>(File(profileDataFile).readText())
        Profile.createProfile(profData)
        SpaceTradersClient.createClient("$DEFAULT_PROF_DIR/authtoken.secret")
        SpaceTradersClient.beginPollingRequests()
        println("Name ${profData.name}")
        agent = (getAgentData() ?: failedToLoad("Agent")) as Agent
        println("HQ @ ${agent.headquarters}")
        loadAllData()
        refreshSystem(OrbitalNames.getSectorSystem(agent.headquarters)) ?: failedToLoad("Headquarters")
        return this
    }

    private fun loadAllData() {
        systems = loadDataFromJsonFile<System>("systems")
        shipyards = loadDataFromJsonFile<ShipyardResults>("shipyards")
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

fun api(params: String): String = "$GameApi/$params"
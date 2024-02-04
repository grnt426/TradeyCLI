package data

import client.SpaceTradersClient
import data.system.System
import data.system.OrbitalNames
import io.ktor.client.request.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

const val DEFAULT_PROF_DIR = "profile"
const val DEFAULT_PROF_FILE = "$DEFAULT_PROF_DIR/profile.settings.json"
object GameState {

    lateinit var profData: ProfileData
    lateinit var agent: Agent
    lateinit var systems: MutableMap<String, System>

    fun initializeGameState(profileDataFile: String = DEFAULT_PROF_FILE): GameState {
        profData = Json.decodeFromString<ProfileData>(File(profileDataFile).readText())
        Profile.createProfile(profData)
        SpaceTradersClient.createClient("$DEFAULT_PROF_DIR/authtoken.secret")
        println("Name ${profData.name}")
        agent = (getAgentData() ?: failedToLoad("Agent")) as Agent
        println("HQ @ ${agent.headquarters}")
        systems = loadSystems()
        refreshSystem(OrbitalNames.getSectorSystem(agent.headquarters)) ?: failedToLoad("Headquarters")
        return this
    }

    private fun loadSystems(): MutableMap<String, System> {
        val systems = mutableMapOf<String, System>()
        File("$DEFAULT_PROF_DIR/systems").walk().forEach { s ->
            if(s.isFile && s.canRead()) {
                println("Attempting to load file ${s.name}")
                systems[s.nameWithoutExtension.uppercase()] = Json.decodeFromString(s.readText())
            }
        }

        return systems
    }

    fun getHqSystem(): System = systems[OrbitalNames.getSectorSystem(agent.headquarters)]!!

    private fun failedToLoad(what: String) {
        throw ProfileLoadingFailure("Failed to load '$what' data.")
    }

    private fun getAgentData(): Agent? = SpaceTradersClient.callGet<Agent>(request {
        url("https://api.spacetraders.io/v2/my/agent")
    })

    fun refreshSystem(systemName: String): System? {
        println("Loading ${systemName}")
        val system = SpaceTradersClient.callGet<System>(request {
            url("https://api.spacetraders.io/v2/systems/$systemName")
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
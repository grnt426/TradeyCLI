package startup

import api.ApiClient
import api.ApiError
import api.SpaceTradersApi
import data.ACCOUNT_TOKEN_FILE
import data.AGENT_TOKEN_FILE
import data.DbClient
import data.PriceHistory
import data.SavedScripts
import data.ensureRuntimeDirectories
import data.readSecret
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.ApiJson
import model.BootProgress
import model.DEFAULT_PROF_DIR
import model.DEFAULT_PROF_FILE
import model.GameState
import model.GameState.bootGameStateFromNewAgent
import model.GameState.initializeGameState
import model.ProfileData
import model.exceptions.BootFailure
import model.responsebody.RegisterResponse
import model.saveProfile
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

private val logger = KotlinLogging.logger {}

object BootManager {

    /**
     * Registers a new agent using the symbol and faction from the profile file and the account
     * token in [ACCOUNT_TOKEN_FILE], then boots the game state from the registration response.
     * Caches from any previous agent are wiped only once registration has succeeded.
     *
     * @throws BootFailure with a message for the user when a prerequisite is missing or the API
     * rejects the registration.
     */
    suspend fun bootstrapNew(accountTokenPath: String = ACCOUNT_TOKEN_FILE) {
        ensureRuntimeDirectories()
        val accountToken = readSecret(accountTokenPath) ?: throw BootFailure(
            "No account token. Create one under account settings at https://my.spacetraders.io and paste it into " +
                    "$accountTokenPath on a single line."
        )
        val profDataFile = File(DEFAULT_PROF_FILE)
        val profData = ApiJson.decodeFromString<ProfileData>(profDataFile.readText())
        if (profData.name.length !in 3..14) throw BootFailure(
            "Agent symbol '${profData.name}' must be 3 to 14 characters; edit 'name' in $DEFAULT_PROF_FILE."
        )

        logger.info { "Registering agent ${profData.name} with faction ${profData.faction}" }
        BootProgress.step("Registering ${profData.name} with ${profData.faction}")
        val response = registerAgent(accountToken, profData)
        logger.info { "Registered agent ${response.agent.symbol}, headquarters ${response.agent.headquarters}" }

        deleteAllData()
        DbClient.createClient()
        logger.debug { "Deleting data from tables" }
        transaction {
            if (SavedScripts.exists()) SavedScripts.deleteAll()
            if (PriceHistory.exists()) PriceHistory.deleteAll()
        }

        profData.name = response.agent.symbol
        saveProfile(DEFAULT_PROF_FILE, profData)

        bootGameStateFromNewAgent(profData, response)
    }

    /**
     * Boots from the agent token in [AGENT_TOKEN_FILE].
     *
     * @throws BootFailure with a message for the user when the token is missing or rejected.
     */
    suspend fun normalStart(agentTokenPath: String = AGENT_TOKEN_FILE) {
        ensureRuntimeDirectories()
        if (readSecret(agentTokenPath) == null) throw BootFailure(
            "No agent token at $agentTokenPath. Mint one for your agent at https://my.spacetraders.io and paste " +
                    "it into that file, or type NEW to register a new agent with an account token."
        )
        initializeGameState()
    }

    /** Identical to [normalStart] while ship automation is disabled; kept so the menu entry works. */
    suspend fun debugStart() {
        normalStart()
    }

    private suspend fun registerAgent(accountToken: String, profData: ProfileData): RegisterResponse {
        val data = try {
            ApiClient(accountToken, GameState.pacer).use { client ->
                SpaceTradersApi(client).register(profData.name, profData.faction).jsonObject
            }
        } catch (e: ApiError) {
            val hint = if (e.apiMessage.contains("agent-token")) {
                " That is an agent token. Agent tokens belong in $AGENT_TOKEN_FILE; paste it there and use START. " +
                        "Only an account token from the portal's account settings can register agents."
            } else ""
            throw BootFailure("Registration rejected (HTTP ${e.status}): ${e.apiMessage} (code ${e.code}).$hint")
        }

        // Save the token before decoding anything else. If the models lag the API, the agent still
        // exists on the server and START must be able to use it.
        val token = data["token"]?.jsonPrimitive?.content
        if (token == null) {
            logger.error { "Registration response had no token: $data" }
            throw BootFailure("Registration response had no token; see log.txt.")
        }
        File(AGENT_TOKEN_FILE).writeText(token)
        logger.info { "Agent token saved to $AGENT_TOKEN_FILE" }

        return try {
            ApiJson.decodeFromJsonElement<RegisterResponse>(data)
        } catch (e: SerializationException) {
            logger.error(e) { "Could not decode registration response: $data" }
            throw BootFailure(
                "Agent registered and its token saved, but the response could not be decoded " +
                        "(${e.message}). Fix the model, then use START."
            )
        }
    }

    private fun deleteAllData() {
        logger.info { "Deleting all data from files" }
        deleteFiles("markets")
        deleteFiles("systems")
        deleteFiles("waypoints")
        deleteFiles("ships")
        deleteFiles("shipyards")
        deleteFiles("agent")
        logger.info { "All data deleted in files" }
    }

    private fun deleteFiles(folderName: String) {
        File("$DEFAULT_PROF_DIR/$folderName")
            .walk()
            .filter { f -> f.isFile && f.canWrite() }
            .forEach { f -> f.delete() }
    }
}

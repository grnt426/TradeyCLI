package startup

import data.ACCOUNT_TOKEN_FILE
import data.AGENT_TOKEN_FILE
import data.DbClient
import data.PriceHistory
import data.SavedScripts
import data.ensureRuntimeDirectories
import data.readSecret
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.ApiJson
import model.DEFAULT_PROF_DIR
import model.DEFAULT_PROF_FILE
import model.GameState.bootGameStateFromNewAgent
import model.GameState.initializeGameState
import model.ProfileData
import model.api
import model.exceptions.BootFailure
import model.requestbody.RegisterRequest
import model.responsebody.RegisterResponse
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
            "No account token. Create one at https://my.spacetraders.io and paste it into " +
                    "$accountTokenPath on a single line."
        )
        val profDataFile = File(DEFAULT_PROF_FILE)
        val profData = ApiJson.decodeFromString<ProfileData>(profDataFile.readText())
        if (profData.name.length !in 3..14) throw BootFailure(
            "Agent symbol '${profData.name}' must be 3 to 14 characters; edit 'name' in $DEFAULT_PROF_FILE."
        )

        logger.info { "Registering agent ${profData.name} with faction ${profData.faction}" }
        val response = createAccountClient().use { client -> registerAgent(client, accountToken, profData) }
        logger.info { "Registered agent ${response.agent.symbol}, headquarters ${response.agent.headquarters}" }

        deleteAllData()
        DbClient.createClient()
        logger.debug { "Deleting data from tables" }
        transaction {
            if (SavedScripts.exists()) SavedScripts.deleteAll()
            if (PriceHistory.exists()) PriceHistory.deleteAll()
        }

        profData.name = response.agent.symbol
        profDataFile.writeText(ApiJson.encodeToString(profData))

        bootGameStateFromNewAgent(profData, response)
    }

    /**
     * Boots from the agent token in [AGENT_TOKEN_FILE].
     *
     * @throws BootFailure with a message for the user when the token is missing or rejected.
     */
    fun normalStart(agentTokenPath: String = AGENT_TOKEN_FILE) {
        ensureRuntimeDirectories()
        if (readSecret(agentTokenPath) == null) throw BootFailure(
            "No agent token at $agentTokenPath. Type NEW to register an agent for this reset, " +
                    "or paste an existing agent token into that file."
        )
        initializeGameState()
    }

    /** Identical to [normalStart] while ship automation is disabled; kept so the menu entry works. */
    suspend fun debugStart() {
        normalStart()
    }

    private suspend fun registerAgent(
        client: HttpClient, accountToken: String, profData: ProfileData
    ): RegisterResponse {
        val response = client.post(api("register")) {
            header(HttpHeaders.Authorization, "Bearer $accountToken")
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(profData.name, profData.faction))
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            logger.error { "Registration failed: ${response.status} - $body" }
            throw BootFailure("Registration rejected (HTTP ${response.status.value}): ${describeApiError(body)}")
        }

        val data = envelopeField(body, "data")
        if (data == null) {
            logger.error { "Registration response had no data object: $body" }
            throw BootFailure("Registration response had no data object; see log.txt.")
        }

        // Save the token before decoding anything else. If the models lag the API, the agent still
        // exists on the server and START must be able to use it.
        val token = data["token"]?.jsonPrimitive?.content
        if (token == null) {
            logger.error { "Registration response had no token: $body" }
            throw BootFailure("Registration response had no token; see log.txt.")
        }
        File(AGENT_TOKEN_FILE).writeText(token)
        logger.info { "Agent token saved to $AGENT_TOKEN_FILE" }

        return try {
            ApiJson.decodeFromJsonElement<RegisterResponse>(data)
        } catch (e: SerializationException) {
            logger.error(e) { "Could not decode registration response: $body" }
            throw BootFailure(
                "Agent registered and its token saved, but the response could not be decoded " +
                        "(${e.message}). Fix the model, then use START."
            )
        }
    }

    /** Pulls the message and code out of the API's error envelope, falling back to the raw body. */
    private fun describeApiError(body: String): String {
        val error = envelopeField(body, "error") ?: return body.take(300)
        val message = error["message"]?.jsonPrimitive?.content ?: body.take(300)
        val code = error["code"]?.jsonPrimitive?.content
        return if (code != null) "$message (code $code)" else message
    }

    private fun envelopeField(body: String, field: String): JsonObject? =
        runCatching { ApiJson.parseToJsonElement(body).jsonObject[field]?.jsonObject }.getOrNull()

    private fun createAccountClient(): HttpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(ApiJson)
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

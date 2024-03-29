package startup

import data.DbClient
import data.PriceHistory
import data.SavedScripts
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import model.DEFAULT_PROF_DIR
import model.GameState.bootGameStateFromNewAgent
import model.GameState.initializeGameState
import model.ProfileData
import model.api
import model.faction.FactionSymbol
import model.requestbody.RegisterRequest
import model.responsebody.RegisterResponse
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.exists
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

private val logger = KotlinLogging.logger {}
object BootManager {

    suspend fun bootstrapNew() {
        try {
            logger.info { "Creating new Agent" }

            // delete all previous data
            deleteAllData()

            // empty tables
            DbClient.createClient()
            logger.debug { "Deleting data from tables" }
            transaction {
                if (SavedScripts.exists()) SavedScripts.deleteAll()
                if (PriceHistory.exists()) PriceHistory.deleteAll()
            }
            logger.info { "data deleted from tables" }

            // We don't have auth until the Agent is generated
            val client = createNoAuthClient()
            val resp = createAgent(client) ?: throw Exception("Failed to create agent???")

            // save auth token
            File("$DEFAULT_PROF_DIR/authtoken.secret").writeText(resp.token)

            // get previous profile data and update with name
            val profDataFile = File("$DEFAULT_PROF_DIR/profile.settings.json")
            val profData = Json.decodeFromString<ProfileData>(
                profDataFile.readText()
            )
            profData.name = resp.agent.symbol
            profDataFile.writeText(Json.encodeToString(profData))

            bootGameStateFromNewAgent(profData, resp)
        } catch (e: Exception) {
            logger.error(e) { "Failed to initialize agent" }
        }
    }

    fun normalStart() {
        initializeGameState()
    }

    suspend fun debugStart() {

    }

    private fun createAgent(client: HttpClient): RegisterResponse? = runBlocking {
        val response = client.post( request {
            url(api("register"))
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("TripleHat12", FactionSymbol.VOID))
        })

        logger.info { "Got body of new agent" }
        logger.info { runBlocking { response.bodyAsText() } }

        return@runBlocking Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
            Json.decodeFromJsonElement<RegisterResponse>(
                it
            )
        }
    }

    private fun createNoAuthClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json()
            }
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
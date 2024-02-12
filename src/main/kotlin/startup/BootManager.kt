package startup

import data.DbClient
import data.SavedScripts
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
import model.*
import model.GameState.bootGameStateFromNewAgent
import model.faction.FactionSymbol
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import requestbody.RegisterRequest
import responsebody.RegisterResponse
import java.io.File

object BootManager {

    fun bootstrapNew() {

        // delete all previous data
        deleteAllData()

        // empty tables
        DbClient.createClient()
        transaction { SavedScripts.deleteAll() }

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
    }

    fun normalStart() {
        GameState.initializeGameState()
        GameState.fetchAllShips()
    }

    fun debugStart() {

    }

    private fun createAgent(client: HttpClient): RegisterResponse? = runBlocking {
        val response = client.post( request {
            url(api("register"))
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("TripleHat3", FactionSymbol.VOID))
        })

        println("Got body of new agent")
        println(response.bodyAsText())

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
        deleteFiles("markets")
        deleteFiles("systems")
        deleteFiles("waypoints")
        deleteFiles("ships")
        deleteFiles("shipyards")
    }

    private fun deleteFiles(folderName: String) {
        File("$DEFAULT_PROF_DIR/$folderName")
            .walk()
            .filter { f -> f.isFile && f.canWrite() }
            .forEach { f -> f.delete() }
    }
}
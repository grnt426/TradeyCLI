import client.SpaceTradersClient
import com.varabyte.kotter.foundation.LiveVar
import com.varabyte.kotter.foundation.anim.TextAnim
import com.varabyte.kotter.foundation.anim.text
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.foundation.input.*
import com.varabyte.kotter.foundation.liveVarOf
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.render.RenderScope
import com.varabyte.kotterx.grid.Cols
import com.varabyte.kotterx.grid.grid
import model.*
import model.GameState.initializeGameState
import model.system.System
import model.system.WaypointType
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import model.GameState.fetchAllShips
import model.market.TradeGood
import model.market.TradeSymbol
import model.ship.components.Inventory
import model.ship.getShips
import model.system.Waypoint
import java.awt.Color
import java.io.File
import java.lang.Thread.sleep
import java.time.LocalDateTime
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration.Companion.milliseconds

enum class Window {
    MAIN,
    CONTRACT,
    SHIP,
    WAYPOINTS,
    WAYPOINTS_INFO,
}

enum class RequestStatus {
    STARTED,
    SUCCESS,
    TIMEOUT,
    FAILED,
    NONE
}

val ACTIONS = listOf("help", "contracts", "accept", "waypoints")
val client = HttpClient(CIO) {
    install(Auth) {
        bearer {
            loadTokens {
                val token = File("profile/authtoken.secret").readText()
                BearerTokens(token, token)
            }
        }
    }
    install(ContentNegotiation) {
        json()
    }
}

var awaitContract = RequestStatus.NONE
lateinit var renderScope: MainRenderScope
var waypointsInSystem: List<System> = emptyList()
val commandHistory = ArrayDeque<String>(emptyList())
var commandHistoryIndex = 0
var shipsInShipyard: ShipyardResults? = null

val notifications = mutableListOf("Nothing to report...")

lateinit var liveCredits: LiveVar<Long>

enum class QuadSelect {
    MAIN,
    SUMM,
    COMM,
    NOTF,
    NONE
}

fun main() {
    val gameState = initializeGameState()
    fetchAllShips()

    val HEADER_COLOR = Color(149, 149, 240)
    val SELECTED_HEADER_COLOR = Color(26, 208, 222)

    var agent = gameState.agent
    var hq = gameState.getHqSystem()
    val profileData = gameState.profData
//    val agentData = readAgentData()["data"]?.jsonObject
//    val faction = Json.decodeFromString<Faction>(agentData?.get("faction").toString())
//    val ship = Json.decodeFromString<Ship>(agentData?.get("ship").toString())
//    val contract = Json.decodeFromString<Contract>(agentData?.get("contract").toString())
    var currentView = Window.MAIN
    var setInputTo: String? = null

    session {
        var selectedQuad by liveVarOf(QuadSelect.NONE)
        val notificationBlink = TextAnim.Template(listOf(" + ", " - "), 500.milliseconds)
        val anim = textAnimOf(notificationBlink)
        liveCredits = liveVarOf(agent.credits)

        section {
            renderScope = this

            grid(cols = Cols.uniform(2, gameState.profData.termWidth / 2)) {
                cell {
                    val headerColor = if (selectedQuad != QuadSelect.MAIN) HEADER_COLOR else SELECTED_HEADER_COLOR
                    when (currentView) {
                        Window.MAIN -> {
                            rgb(headerColor.rgb) {
                                makeHeader("Ship Status: ${hq.symbol}")
                            }

                            getShips().forEach { s ->
                                text("${s.registration.name} - ${s.script?.currentState}")
                                if (s.cooldown.remainingSeconds > 0)
                                    textLine(" (${s.cooldown.remainingSeconds})")
                                else
                                    textLine()
                                if (s.cargo.capacity > 0) {
                                    textLine(s.cargo.inventory.chunked(2).joinToString("\n", transform = { chunk ->
                                        chunk.joinToString (", ", transform = { c: Inventory ->
                                            " * ${c.units} ${c.name}"
                                        })
                                    }))
                                }
                            }
                        }

                        Window.CONTRACT -> {
                            textLine("id - type - ${if (true) "Accepted" else "Waiting"}")
//                            val deliveryTerms = contract.terms.deliver[0]
//                            textLine("${deliveryTerms.unitsRequired} ${deliveryTerms.tradeSymbol} to ${deliveryTerms.destinationSymbol}")

                            when (awaitContract) {
                                RequestStatus.STARTED -> textLine("Awaiting Response to contract acceptance")
                                RequestStatus.SUCCESS -> textLine("Contract accepted")
                                RequestStatus.TIMEOUT -> textLine("Contract timeout")
                                RequestStatus.FAILED -> textLine("Failed to accept contract")
                                RequestStatus.NONE -> textLine("No contracts in process")
                            }
                        }

                        Window.SHIP -> TODO()
                        Window.WAYPOINTS -> {
                            waypointsInSystem.forEach { wp ->
                                textLine("${wp.type} ${wp.symbol}")
                            }
                        }

                        Window.WAYPOINTS_INFO -> TODO()
                    }
                }

                cell {
                    val headerColor = if (selectedQuad != QuadSelect.SUMM) HEADER_COLOR else SELECTED_HEADER_COLOR
                    rgb(headerColor.rgb) {
                        makeHeader("Agent")
                    }
                    textLine("${agent.symbol} - $${liveCredits.value}")
                    textLine(" - ")
                    textLine(" flying with  in ")
                }

                cell {
                    val headerColor = if (selectedQuad != QuadSelect.COMM) HEADER_COLOR else SELECTED_HEADER_COLOR
                    rgb(headerColor.rgb) {
                        makeHeader("Command")
                    }
                    textLine("Current command stuff")
                }

                cell {
                    val headerColor = if (selectedQuad != QuadSelect.NOTF) HEADER_COLOR else SELECTED_HEADER_COLOR
                    rgb(headerColor.rgb) {
                        makeHeader("Notifications")
                    }
                    textLine(" * Old Notification")
                    green { text(anim) }
                    textLine("New Notification")
                    green { text(anim) }
                    textLine("New Notification")
                }
            }
            text("> ");
            (if(setInputTo != null) setInputTo else "")?.let { initial ->
                input(Completions(*ACTIONS.toTypedArray()),
                    initial
                )
            }
        }.runUntilKeyPressed(Keys.ESC) {
            onKeyPressed {
                println("Pressed: $key ${key.hashCode()}")
                when(key) {
                    Keys.DIGIT_1 -> {
                        val inputLen = (getInput()?.length ?: 0)
                        println("Input len $inputLen")
                        if (inputLen <= 1) {
                            selectedQuad = QuadSelect.MAIN
                            setInput("")
                            println("One pressed")
                        }
                    }
                    Keys.DIGIT_2 -> {
                        val inputLen = (getInput()?.length ?: 0)
                        println("Input len $inputLen")
                        if (inputLen <= 1) {
                            selectedQuad = QuadSelect.SUMM
                            setInput("")
                            println("Two pressed")
                        }
                    }
                    Keys.DIGIT_3 -> {
                        val inputLen = (getInput()?.length ?: 0)
                        println("Input len $inputLen")
                        if (inputLen <= 1) {
                            selectedQuad = QuadSelect.COMM
                            setInput("")
                            println("Three pressed")
                        }
                    }
                    Keys.DIGIT_4 -> {
                        val inputLen = (getInput()?.length ?: 0)
                        println("Input len $inputLen")
                        if (inputLen <= 1) {
                            selectedQuad = QuadSelect.NOTF
                            setInput("")
                            println("Four pressed")
                        }
                    }

                    // Doesn't quite work...
                    Keys.UP -> {
                        if(commandHistory.size > 0 && commandHistoryIndex < commandHistory.size){
                            setInputTo = commandHistory[commandHistoryIndex]
                            commandHistoryIndex = min(commandHistory.size - 1, commandHistoryIndex++)
                            rerender()
                        }
                    }
                    Keys.DOWN -> {
                        if (commandHistory.size > 0 && commandHistoryIndex >= 0) {
                            setInputTo = commandHistory[commandHistoryIndex]
                            commandHistoryIndex = max(0, commandHistoryIndex--)
                            rerender()
                        }
                    }
                    Keys.TAB -> {
                        if (selectedQuad != QuadSelect.NONE) {
                            selectedQuad = QuadSelect.NONE
                        }
                    }
                }
            }

            onInputEntered {
                if (selectedQuad == QuadSelect.NONE) {
                    commandHistoryIndex = 0
                    commandHistory.addFirst(input)
                    val args = input.split(" ").map { str -> str.uppercase() }
                    val command = args[0]
                    when (args.size) {
                        1 -> {
                            when (command) {
                                "CONTRACTS" -> currentView = Window.CONTRACT
                            }
                            this.clearInput()
                        }

                        2 -> {
                            when (command) {
                                "ACCEPT" -> acceptContract(args[1], this)
                            }
                            this.clearInput()
                        }

                        3 -> {
                            when (command) {
                                "WAYPOINTS" -> {
                                    currentView = Window.WAYPOINTS
                                    getWaypoints(args[1], args[2])
                                }

                                "PURCHASESHIP" -> {
                                    purchaseShip(args[1], args[2])
                                }
                            }
                            this.clearInput()
                        }

                        4 -> {
                            when (command) {
                                "WAYPOINTINFO" -> {
                                    currentView = Window.WAYPOINTS_INFO
                                    getWaypointInfo(args[1], args[2], args[3])
                                }
                            }
                        }
                    }
                }
                else {
                    println("Ignoring for different window context")
                }
            }
        }
    }

    client.close()
}

inline fun <reified T: LastRead> callGet(request: HttpRequestBuilder): T? {
    var result: T? = null
    runBlocking {
        launch {
            try {
                withTimeout(2_000) {
                    val response = client.get(request)
                    if (response.status == HttpStatusCode.OK && response.bodyAsText().isNotEmpty()) {
                        println(response.bodyAsText())
                        result = Json.decodeFromString<JsonObject>(response.bodyAsText())["model"]?.let {
                            Json.decodeFromJsonElement<T>(
                                it
                            )
                        }!!
                        result!!.timestamp = LocalDateTime.now().toString()
                    } else {
                        println("${response.status} - ${response.bodyAsText()}")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                println("Timeout")
            }
        }
    }
    return result
}

fun getWaypointInfo(systemSymbol: String, waypointSymbol: String, target: String) {
    runBlocking {
        launch {
            try {
                withTimeout(2_000) {
                    val response = client.get {
                        url("https://api.spacetraders.io/v2/systems/${systemSymbol.uppercase()}/waypoints/${waypointSymbol.uppercase()}/$target")
                    }
                    if (response.status == HttpStatusCode.OK && response.bodyAsText().isNotEmpty()) {
                        println(response.bodyAsText())
                        shipsInShipyard = Json.decodeFromString<JsonObject>(response.bodyAsText())["model"]?.let {
                            Json.decodeFromJsonElement<ShipyardResults>(
                                it
                            )
                        }!!
                    } else {
                        println("${response.status} - ${response.bodyAsText()}")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                println("Timeout getting waypoint data @ $systemSymbol for $waypointSymbol")
            }
        }
    }
}

fun purchaseShip(shipType: String, waypointSymbol: String) {

    @Serializable
    data class ShipPurchase(val shipType: String, val waypointSymbol: String)

    runBlocking {
        launch {
            try {
                withTimeout(2_000) {
                    val response = client.post {
                        url("https://api.spacetraders.io/v2/my/ships")
                        contentType(ContentType.Application.Json)
                        setBody(ShipPurchase(shipType, waypointSymbol))
                    }
                    if (response.status == HttpStatusCode.OK) {
                        println("Success ${response.bodyAsText()}")
                    } else {
                        println("${response.status} - ${response.bodyAsText()}")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                println("Timeout buying ship @ $shipType for $waypointSymbol")
            }
        }
    }
}

/**
 * TODO: Paginated API
 */
fun getWaypoints(systemSymbol: String, trait: String) {
    runBlocking {
        launch {
            try {
                withTimeout(2_000) {
                    val response = client.get {
                        url("https://api.spacetraders.io/v2/systems/$systemSymbol/waypoints")
                        parameter("traits", trait)
                        println("Built URL ${this.url}")
                    }
                    if (response.status == HttpStatusCode.OK && response.bodyAsText().isNotEmpty()) {
                        waypointsInSystem = Json.decodeFromString<JsonObject>(response.bodyAsText())["model"]?.let {
                            Json.decodeFromJsonElement<List<System>>(
                                it
                            )
                        }!!
                        println(response.bodyAsText())
                    } else {
                        println("${response.status} - ${response.bodyAsText()}")
                    }
                }
            } catch (e: TimeoutCancellationException) {
                println("Timeout getting waypoints @ $systemSymbol for $trait")
            }
        }
    }
}

fun acceptContract(id: String, onInputEnteredScope: OnInputEnteredScope) {
    runBlocking {
        launch {
            awaitContract = RequestStatus.STARTED
            try {
                withTimeout(2_000) {
                    val response = client.post {
                        url("https://api.spacetraders.io/v2/my/contracts/${id}/accept")
                    }
                    awaitContract = if (response.status == HttpStatusCode.OK) {
                        RequestStatus.SUCCESS
                    } else {
                        println("${response.status} - ${response.bodyAsText()}")
                        RequestStatus.FAILED
                    }
                }
            }
            catch (e: TimeoutCancellationException) {
                awaitContract = RequestStatus.TIMEOUT
            }
        }
    }
}

suspend fun createAgent() {
    val agentData: JsonObject = Json.decodeFromString(HttpClient(CIO).use { c ->
        c.post {
            url("https://api.spacetraders.io/v2/register")
            header("Content-Type", "application/json")
            setBody(
                """
                    {
                        "symbol": "TripleHat",
                        "faction": "COSMIC"
                   }
                """.trimIndent()
            )
        }
    }.bodyAsText())

    // write to file
}

fun RenderScope.makeHeader(text: String) {
    underline {
        text(text)
        repeat(Profile.profileData.termWidth / 2 - text.length) { text(" ") }
    }
    textLine()
}

suspend fun readAgentData(): JsonObject = Json.decodeFromString(File("agentdata.secret").readText())
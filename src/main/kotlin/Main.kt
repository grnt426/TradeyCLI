import com.varabyte.kotter.foundation.input.*
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.MainRenderScope
import data.Agent
import data.Faction
import data.ShipyardResults
import data.contract.Contract
import data.ship.Ship
import data.system.Orbital
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.math.min

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
                val token = File("authtoken.secret").readText()
                BearerTokens(token, token)
            }
        }
    }
}

var awaitContract = RequestStatus.NONE
lateinit var renderScope: MainRenderScope
var waypointsInSystem: List<Orbital> = emptyList()
val commandHistory = ArrayDeque<String>(emptyList())
var commandHistoryIndex = 0
var shipsInShipyard: ShipyardResults? = null

val notifications = mutableListOf("Nothing to report...")

suspend fun main() {

    gridWithSomeEmptyCells()

    gridWithWrappedText()

//    theScreen()

    return

    val agentData = readAgentData()["data"]?.jsonObject
    val agent = Json.decodeFromString<Agent>(agentData?.get("agent").toString())
    val faction = Json.decodeFromString<Faction>(agentData?.get("faction").toString())
    val ship = Json.decodeFromString<Ship>(agentData?.get("ship").toString())
    val contract = Json.decodeFromString<Contract>(agentData?.get("contract").toString())
    var currentView = Window.MAIN
    var setInputTo: String? = null

    session {
        section {
            renderScope = this
            when(currentView) {
                Window.MAIN -> {
                    textLine("${agent.symbol} - $${agent.credits}")
                    textLine("${faction.name} - ${faction.description}")
                    textLine("${ship.registration.name} flying with ${ship.crew.current} in ${ship.nav.systemSymbol}")
                    textLine("Please accept by ${contract.deadlineToAccept} and deliver ${contract.terms.deliver[0].tradeSymbol}")
                }
                Window.CONTRACT -> {
                    textLine("${contract.id} - ${contract.type} - ${if(contract.accepted) "Accepted" else "Waiting"}")
                    val deliveryTerms = contract.terms.deliver[0]
                    textLine("${deliveryTerms.unitsRequired} ${deliveryTerms.tradeSymbol} to ${deliveryTerms.destinationSymbol}")

                    when(awaitContract) {
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
            text("> ");
            (if(setInputTo != null) setInputTo else "")?.let { inital ->
                input(Completions(*ACTIONS.toTypedArray()),
                    inital
                )
            }
        }.runUntilKeyPressed(Keys.ESC) {

            // Doesn't quite work...
            onKeyPressed {
                when(key) {
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
                }
            }

            onInputEntered {
                commandHistoryIndex = 0
                commandHistory.addFirst(input)
                val args = input.split(" ")
                val command = args[0]
                when(args.size) {
                    1 -> {
                        when(command) {
                            "contracts" -> currentView = Window.CONTRACT
                        }
                        this.clearInput()
                    }
                    2 -> {
                        when(command) {
                            "accept" -> acceptContract(args[1], this)
                        }
                        this.clearInput()
                    }
                    3 -> {
                        when(command) {
                            "waypoints" -> {
                                currentView = Window.WAYPOINTS
                                getWaypoints(args[1].uppercase(), args[2].uppercase())
                            }
                        }
                        this.clearInput()
                    }
                    4 -> {
                        when(command) {
                            "waypointInfo" -> {
                                currentView = Window.WAYPOINTS_INFO
                                getWaypointInfo(args[1], args[2], args[3])
                            }
                        }
                    }
                }
            }
        }
    }

    client.close()
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
                        shipsInShipyard = Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
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
                        println("Built URL ${this.url.toString()}")
                    }
                    if (response.status == HttpStatusCode.OK && response.bodyAsText().isNotEmpty()) {
                        waypointsInSystem = Json.decodeFromString<JsonObject>(response.bodyAsText())["data"]?.let {
                            Json.decodeFromJsonElement<List<Orbital>>(
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

fun gridWithWrappedText() {
    session {
        section {
            grid(width = 30, columns = 2) {
                cell {
                    wrapTextLine("cell 1 line 1 way too long I think")
                    textLine("cell 1 line 2")
                }
                cell {
                    textLine("cell 2 line 1")
                    textLine("cell 2 line 2")
                    textLine("cell 2 line 3")
                }
            }
        }.runUntilKeyPressed(Keys.ESC)
    }
}

fun gridWithSomeEmptyCells() {
    session {
        section {
            grid(width = 30, columns = 3) {
                cell {
                    yellow {
                        textLine("cell 1 line 1")
                        textLine("cell 1 line 2")
                    }
                }
                cell {

                }
                cell {
                    textLine("cell 3 line 1")
                    textLine("cell 3 line 2")
                    textLine("cell 3 line 3")
                }

                cell {
                    textLine("cell 4 line 1")
                    textLine("cell 4 line 2")
                    textLine("cell 4 line 3")
                }
                cell {
                    textLine("cell 5 line 1")
                    textLine("cell 5 line 2")
                    textLine("cell 5 line 3")
                }
                cell {

                }
            }
        }.runUntilKeyPressed(Keys.ESC)
    }
}

fun blockLineWrap(text: String, width: Int): StringBuilder {
    val lines = text.lines()
    return if (lines.size > 1) {
        val sb = StringBuilder()
        lines.forEach { l ->
            println("Wrapping line")
            textLineWrap(l, width, sb)
        }
        println("Finished block wrap of quad")
        sb
    }
    else {
        StringBuilder(text)
    }
}

fun textLineWrap(text: String, width: Int, sb: StringBuilder) {
    if (text.length > width || text.lines().size > 1) {
        val chunks = text.length / width
        var index = 0
        while (index <= chunks) {
            println("Breaking line")
            val storing = text.substring(index * width, min((index + 1) * width, text.length)).trim()
            sb.appendLine(storing)
            index++
        }
        println("Done breaking line")
    }
    else {
        sb.appendLine(text)
    }
}

suspend fun readAgentData(): JsonObject = Json.decodeFromString(File("agentdata.secret").readText())
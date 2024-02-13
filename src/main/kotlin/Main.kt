import AppState.*
import client.SpaceTradersClient
import com.varabyte.kotter.foundation.anim.TextAnim
import com.varabyte.kotter.foundation.anim.text
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.foundation.input.*
import com.varabyte.kotter.foundation.render.aside
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import com.varabyte.kotter.runtime.render.RenderScope
import com.varabyte.kotterx.grid.Cols
import com.varabyte.kotterx.grid.grid
import model.*
import model.GameState.agent
import model.GameState.getHqSystem
import model.ship.*
import model.ship.components.Inventory
import model.ship.components.shortName
import startup.BootManager.bootstrapNew
import startup.BootManager.debugStart
import startup.BootManager.normalStart
import java.awt.Color
import java.time.Instant
import kotlin.collections.ArrayDeque
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

enum class Window {
    MAIN,
    CONTRACT,
    SHIP,
    WAYPOINTS,
    WAYPOINTS_INFO,
}

val magnitudes = listOf("", "k", "M", "G", "T", "P")

val ACTIONS = listOf("help", "contracts", "accept", "waypoints")

val commandHistory = ArrayDeque<String>(emptyList())
var commandHistoryIndex = 0

val notifications = mutableListOf("Nothing to report...")

enum class AppState {
    BOOT,
    RUNNING,
    SHUTDOWN
}
enum class QuadSelect {
    MAIN,
    SUMM,
    COMM,
    NOTF,
    NONE
}
var appState = BOOT

val HEADER_COLOR = Color(149, 149, 240)
val SELECTED_HEADER_COLOR = Color(26, 208, 222)
val bootContext = BootRenderContext()
val runningRenderContext = RunningRenderContext()

val notificationBlink = TextAnim.Template(listOf(" + ", " - "), 500.milliseconds)
fun main() {
    session {
        runningRenderContext.textAnim = textAnimOf(notificationBlink)
        section {
            if (appState == BOOT) {
                renderBootSection()
            }
            else if (appState == RUNNING) {
                renderRunningSection()
            }
        }.runUntilKeyPressed(Keys.ESC) {
            delegateToInputManagers()
        }
    }
    SpaceTradersClient.client.close()
}

private fun RunScope.delegateToInputManagers() {
    val runScope = this
    onInputEntered {
        if (appState == BOOT)
            bootOnInputManager(runScope)
        else if (appState == RUNNING)
            runningOnInputManager()
    }
    onKeyPressed {
        if (appState == RUNNING)
            runningOnKeyPressedManager(runScope)
    }
}

fun OnInputEnteredScope.runningOnInputManager() {
    if (runningRenderContext.selectedQuad == QuadSelect.NONE) {
        commandHistoryIndex = 0
        commandHistory.addFirst(input)
        val args = input.split(" ").map { str -> str.uppercase() }
        val command = args[0]
        when (args.size) {
            1 -> {
                when (command) {
                    "CONTRACTS" -> runningRenderContext.selectedView = Window.CONTRACT
                }
                this.clearInput()
            }

            2 -> {
                when (command) {
                    "SHIP" -> runningRenderContext.selectedShip = args[1].toInt()
                }
                this.clearInput()
            }
        }
    }
    else {
        println("Ignoring for different window context")
    }
}
fun MainRenderScope.renderRunningSection() {
    val selectedQuad = runningRenderContext.selectedQuad
    val currentView = runningRenderContext.selectedView
    val selectedShip = runningRenderContext.selectedShip

    grid(cols = Cols.uniform(2, GameState.profData.termWidth / 2)) {
        cell {
            val headerColor = if (selectedQuad != QuadSelect.MAIN) HEADER_COLOR else SELECTED_HEADER_COLOR
            when (currentView) {
                Window.MAIN -> {
                    rgb(headerColor.rgb) {
                        makeHeader("Fleet Status: ${getHqSystem().symbol}")
                    }

                    getShips().forEach { s ->
                        text("${shortName(s)} [")
                        applyShipRoleColor(s.registration.role)
                        text("] ${s.script?.currentState}")
                        if (s.cooldown.remainingSeconds > 0)
                            textLine(" (${s.cooldown.remainingSeconds})")
                        else if (s.nav.status == ShipNavStatus.IN_TRANSIT) {
                            val arrival = Instant.parse(s.nav.route.arrival)
                            val secondsRemaining = arrival.epochSecond.minus(Instant.now().epochSecond).toDouble()

                            textLine(" (${reduceToSiNotation(secondsRemaining, "s")})")
                        }
                        else
                            textLine()
                        if (s.cargo.inventory.isNotEmpty()) {
                            text(" * ")
                            textLine(s.cargo.inventory.chunked(2).joinToString("\n * ", transform = { chunk ->
                                chunk.joinToString (", ", transform = { c: Inventory ->
                                    "${c.units} ${c.name}"
                                })
                            }))
                        }
                    }
                }

                Window.CONTRACT -> {
               }

                Window.SHIP -> TODO()
                Window.WAYPOINTS -> {
                    

                }

                Window.WAYPOINTS_INFO -> TODO()
            }
        }

        cell {
            
            val headerColor = if (selectedQuad != QuadSelect.SUMM) HEADER_COLOR else SELECTED_HEADER_COLOR
            rgb(headerColor.rgb) {
                makeHeader("Agent")
            }
            textLine("${GameState.agent.symbol} - $${agent.credits}")
            textLine(" - ")
            textLine(" flying with  in ")
        }

        cell {
            
            val headerColor = if (selectedQuad != QuadSelect.COMM) HEADER_COLOR else SELECTED_HEADER_COLOR
            rgb(headerColor.rgb) {
                makeHeader("Command")
            }
            val ship = getShips()[selectedShip-1]
            text("${ship.registration.name} [")
            applyShipRoleColor(ship.registration.role, false)
            textLine("] - ${ship.nav.flightMode}")
            text(" C: ${ship.cargo.units}/${ship.cargo.capacity} F: ${ship.fuel.current}/${ship.fuel.capacity} ")
            textLine(if(ship.script != null) "${ship.script!!.currentState}" else "NS")
            if (hasCooldown(ship)) {
                val expire = if (ship.cooldown.remainingSeconds > 0) {
                    ship.cooldown.remainingSeconds
                }
                else {
                    calculateExpirationSeconds(ship)
                }
                textLine("Cool: ${reduceToSiNotation(expire.toDouble(), "s")}")
            }
        }

        cell {
            
            val headerColor = if (selectedQuad != QuadSelect.NOTF) HEADER_COLOR else SELECTED_HEADER_COLOR
            val anim = runningRenderContext.textAnim
            rgb(headerColor.rgb) {
                makeHeader("Notifications")
            }
            textLine(" * Old Notification")
            green { if (anim != null) text(anim) else text("+") }
            textLine("New Notification")
            green { if (anim != null) text(anim) else text("+") }
            textLine("New Notification")
        }
    }
    text("> ");
    input(Completions(*ACTIONS.toTypedArray()), "help")
}

fun OnInputEnteredScope.bootOnInputManager(runScope: RunScope) {
    if (appState == BOOT) {
        when (input.uppercase()) {
            "NEW" -> {
                if (bootContext.userAskedNew) {
                    println("NEW AGENT")
                    bootstrapNew()
                    appState = RUNNING
                } else {
                    runScope.aside { textLine("Type NEW again to confirm") }
                    bootContext.userAskedNew = true
                }
            }

            "START" -> {
                println("NORMAL START")
                normalStart()
                appState = RUNNING
            }

            "DEBUG" -> {
                println("DEBUG START")
                debugStart()
                appState = RUNNING
            }

            else -> {
                println("Exiting")
                exitProcess(1)
            }
        }
    }
}

fun OnKeyPressedScope.runningOnKeyPressedManager(runScope: RunScope) {
    println("Pressed: $key ${key.hashCode()}")
    when(key) {
        Keys.DIGIT_1 -> {
            val inputLen = (runScope.getInput()?.length ?: 0)
            println("Input len $inputLen")
            if (inputLen <= 1) {
                runningRenderContext.selectedQuad = QuadSelect.MAIN
                runScope.setInput("")
            }
        }
        Keys.DIGIT_2 -> {
            val inputLen = (runScope.getInput()?.length ?: 0)
            println("Input len $inputLen")
            if (inputLen <= 1) {
                runningRenderContext.selectedQuad = QuadSelect.SUMM
                runScope.setInput("")
            }
        }
        Keys.DIGIT_3 -> {
            val inputLen = (runScope.getInput()?.length ?: 0)
            println("Input len $inputLen")
            if (inputLen <= 1) {
                runningRenderContext.selectedQuad = QuadSelect.COMM
                runScope.setInput("")
            }
        }
        Keys.DIGIT_4 -> {
            val inputLen = (runScope.getInput()?.length ?: 0)
            println("Input len $inputLen")
            if (inputLen <= 1) {
                runningRenderContext.selectedQuad = QuadSelect.NOTF
                runScope.setInput("")
            }
        }

        // Doesn't quite work...
        Keys.UP -> {
            if(commandHistory.size > 0 && commandHistoryIndex < commandHistory.size){
                runScope.setInput(commandHistory[commandHistoryIndex])
                commandHistoryIndex = min(commandHistory.size - 1, commandHistoryIndex++)
            }
        }
        Keys.DOWN -> {
            if (commandHistory.size > 0 && commandHistoryIndex >= 0) {
                runScope.setInput(commandHistory[commandHistoryIndex])
                commandHistoryIndex = max(0, commandHistoryIndex--)
            }
        }
        Keys.TAB -> {
            if (runningRenderContext.selectedQuad != QuadSelect.NONE) {
                runningRenderContext.selectedQuad = QuadSelect.NONE
            }
        }
    }
}


fun MainRenderScope.renderBootSection() {
    textLine("New - Create a new Agent")
    textLine("Start - Load everything and start all scripts")
    textLine("Debug - Load data, but don't run scripts")
    text("> ")
    input()
}

fun RenderScope.makeHeader(text: String) {
    underline {
        text(text)
        repeat(Profile.profileData.termWidth / 2 - text.length) { text(" ") }
    }
    textLine()
}

fun RenderScope.applyShipRoleColor(role: String, shorten: Boolean = true) {
    val desig = if (shorten) role[0].toString() else role
    when(role) {
        "EXCAVATOR" ->
            rgb(Color(120, 80, 40).rgb) {
                text(desig)
            }
        "TRANSPORT" ->
            green {
                text(desig)
            }
        "COMMAND" ->
            red {
                text(desig)
            }
        "SATELLITE" ->
            magenta {
                text(desig)
            }
        else ->
            white {
                text(desig)
            }
    }
}

fun reduceToSiNotation(number: Double, unit: String): String {
    var res = number
    var index = 0
    while (res > 999) {
        res /= 1000
        index++
    }
    return "${round(res)}${magnitudes[index]}$unit"
}

data class BootRenderContext(var userAskedNew:Boolean = false)

data class RunningRenderContext (
    var selectedQuad: QuadSelect = QuadSelect.NONE,
    var selectedView: Window = Window.MAIN,
    var selectedShip: Int = 1,
    var textAnim: TextAnim? = null
)
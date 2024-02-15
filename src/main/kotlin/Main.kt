
import AppState.BOOT
import AppState.RUNNING
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
import model.GameState
import model.GameState.agent
import model.GameState.getHqSystem
import model.Profile
import model.ship.*
import model.ship.components.Inventory
import model.ship.components.shortName
import startup.BootManager.bootstrapNew
import startup.BootManager.debugStart
import startup.BootManager.normalStart
import java.awt.Color
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
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
val columns = 3

val notificationBlink = TextAnim.Template(listOf(" + ", " - "), 500.milliseconds)

var firstRender = true
var showSystemScreen = false
fun main() {

    // The below needs to come from the profile.settings.json file
    session {
        runningRenderContext.textAnim = textAnimOf(notificationBlink)
        section {
            if (appState == BOOT) {
                renderBootSection()
            }
            else if (appState == RUNNING) {
                if (firstRender) {
                    firstRender = false
                    text("\\e[8;50;144t")
                }
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
                    "SYSTEM" -> showSystemScreen = true
                    "CONSOLE" -> showSystemScreen = false
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

    if (showSystemScreen) {
        showSystemScreen()
    } else {
        showConsole()
    }
}

fun MainRenderScope.showSystemScreen() {

    data class Point(var x: Double, var y: Double) {
        operator fun plus(point: Point): Point = Point(this.x + point.x, this.y + point.y)
        operator fun minus(point: Point): Point = Point(this.x - point.x, this.y - point.y)
        operator fun times(point: Point): Point = Point(this.x * point.x, this.y * point.y)
        operator fun div(point: Point): Point = Point(this.x / point.x, this.y / point.y)
        override operator fun equals(other: Any?): Boolean {
            if (other is Point) return this.x == other.x && this.y == other.y
            return false
        }

        override fun hashCode(): Int {
            return ((31 * x) + (y * 17)).roundToInt()
        }
    }

    val wp = GameState.waypoints.values
    val centerVector = Point(50.0, 50.0)
//    val maxX = abs(wp.maxBy { w -> abs(w.x) }.x) / 1000.0
//    val maxY = abs(wp.maxBy { w -> abs(w.y) }.y) / 1000.0
    val normalize = Point(50.0, 50.0)

    // initialize empty grid
    val map = Array(100) {
        Array(100) {
            " "
        }
    }

    wp.forEach { w ->
        val pos = (Point(w.x.toDouble(), w.y.toDouble()) / normalize) + centerVector
        if (pos.x in 0.0..100.0 && pos.y in 0.0..100.0)
            map[pos.x.roundToInt()][pos.y.roundToInt()] = w.type.name[0].toString()
    }

    println("Here is final map: $map")

    map.forEach { row ->
        textLine(row.joinToString(""))
    }

    text("> ")
    input(Completions(*ACTIONS.toTypedArray()))
}

fun MainRenderScope.showConsole() {

    val selectedQuad = runningRenderContext.selectedQuad
    val currentView = runningRenderContext.selectedView
    val selectedShip = runningRenderContext.selectedShip

    grid(cols = Cols.uniform(columns, GameState.profData.termWidth / columns)) {
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
            textLine("${agent.symbol} - $${agent.credits}")
        }

        cell {
            val blocks = arrayOf(" ", "▁", "▂", "▃", "▄", "▅", "▆", "▇", "█")
            val FULL_BLOCK = blocks[8]
            rgb(HEADER_COLOR.rgb) {
                makeHeader("Job Pressure")
            }
            val pressureWindow = SpaceTradersClient.jobPressureWindow
            (0..4).forEach { h ->
                if (h < 4)
                    text(" |")
                else
                    text("  ")
                if (h != 3) {
                    pressureWindow.forEach { w ->
                        if (h < 4) {
                            val pressure = SpaceTradersClient.getJobPressure(w)
                            when (pressure) {
                                SpaceTradersClient.JobPressure.LOW -> {
                                    if (h == 2) {
                                        green {
                                            text(blocks[w])
                                        }
                                    }
                                }

                                SpaceTradersClient.JobPressure.OK -> {
                                    yellow {
                                        if (h == 1) {
                                            text(blocks[w - 8])
                                        } else if (h > 1) {
                                            text(FULL_BLOCK)
                                        }
                                    }
                                }

                                SpaceTradersClient.JobPressure.HIGH -> {
                                    red {
                                        if (h > 0) {
                                            text(FULL_BLOCK)
                                        } else {
                                            text(blocks[min(w - 16, 8)])
                                        }
                                    }
                                }
                            }
                        } else {
                            text(if (w > 9) "C" else w.toString())
                        }
                    }
                } else {
                    repeat(pressureWindow.size) { text("=") }
                }
                textLine()
            }
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

        cell {

        }
    }
    text("> ")
    input(Completions(*ACTIONS.toTypedArray()))
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
        repeat(Profile.profileData.termWidth / columns - text.length) { text(" ") }
    }
    textLine()
}

fun RenderScope.applyShipRoleColor(role: ShipRole, shorten: Boolean = true) {
    val desig = if (shorten) role.name[0].toString() else role.name
    when(role) {
        ShipRole.EXCAVATOR ->
            rgb(Color(120, 80, 40).rgb) {
                text(desig)
            }

        ShipRole.TRANSPORT ->
            green {
                text(desig)
            }

        ShipRole.COMMAND ->
            red {
                text(desig)
            }

        ShipRole.SATELLITE ->
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

import AppState.BOOT
import cli.LineMode
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.input.runUntilKeyPressed
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.render.RenderScope
import app.App
import data.ensureRuntimeDirectories
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import model.Profile
import model.loadProfile
import model.ship.ShipRole
import notification.NotificationManager
import screen.*
import java.awt.Color
import kotlin.math.round
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}

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

enum class AppState(val screen: Screen) {
    BOOT(BootScreen()),
    LOADING(LoadingScreen()),
    RUNNING(RunningScreen),
    SHUTDOWN(BootScreen())
}
enum class QuadSelect {
    MAIN,
    SUMM,
    COMM,
    NOTF,
    NONE
}
/** Read by the render thread, written by input handlers and by the boot coroutine. */
@Volatile
var appState = BOOT

val HEADER_COLOR = Color(149, 149, 240)
val SELECTED_HEADER_COLOR = Color(26, 208, 222)
val BROWN_EXCAVATOR_COLOR = Color(120, 80, 40)
val bootContext = BootRenderContext()
val runningRenderContext = RunningRenderContext()

fun isActiveScreen(screen: Screen): Boolean = screen == appState.screen
fun getActiveAppState(): AppState = appState

/**
 * With arguments: line mode, one command and out (see [LineMode]). Without: the dashboard.
 */
suspend fun main(args: Array<String>) {
    ensureRuntimeDirectories()
    if (args.isNotEmpty()) {
        val code = try {
            LineMode().run(args.toList())
        } finally {
            App.shutdown()
        }
        exitProcess(code)
    }

    logger.info { "TradeyCLI starting" }
    Profile.createProfile(loadProfile())
    App.profData = Profile.profileData
    val engine = App.engine

    session {
        with(TextAnimationContainer) {
            createAnimations()
        }
        section {
            with(appState.screen) {
                render()
            }
        }.runUntilKeyPressed(Keys.Escape) {
            val runScope = this
            // The dashboard is a reader of engine state: any change repaints, any event becomes a notification.
            engine.scope.launch {
                launch { engine.state.collect { runScope.rerender() } }
                launch { engine.events.collect { NotificationManager.onEvent(it) } }
            }
            onInputEntered {
                guarded("input") {
                    with(appState.screen) {
                        val prevState = appState
                        appState = onInput(runScope)
                        if (prevState != appState)
                            rerender()
                    }
                }
            }
            onKeyPressed {
                guarded("key press") {
                    with(appState.screen) {
                        appState = onKeyPressed(runScope)
                    }
                }
            }
        }
    }
    App.shutdown()
}

/**
 * Screens run their handlers on Kotter's key-processing coroutine. An exception escaping it kills
 * that coroutine and leaves the screen frozen with no message, so every handler is wrapped: the
 * failure is logged, surfaced as a notification, and the current screen stays put.
 */
private inline fun guarded(what: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        NotificationManager.exceptNotification(
            "Unhandled error while handling $what", e.message ?: e::class.simpleName ?: "unknown error", e
        )
    }
}

fun RenderScope.makeHeader(text: String, spansColumns: Int = 1) {
    val columnWidth = Profile.profileData.termWidth / ConsoleSubScreen.COLUMNS
    underline {
        text(text)
        repeat(columnWidth * spansColumns - text.length + (spansColumns - 1)) { text(" ") }
    }
    textLine()
}

fun RenderScope.applyShipRoleColor(role: ShipRole, shorten: Boolean = true) {
    val desig = if (shorten) role.name[0].toString() else role.name
    when(role) {
        ShipRole.EXCAVATOR ->
            rgb(BROWN_EXCAVATOR_COLOR.rgb) {
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
)

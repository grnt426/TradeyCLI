
import AppState.BOOT
import client.SpaceTradersClient
import com.varabyte.kotter.foundation.anim.TextAnim
import com.varabyte.kotter.foundation.anim.textAnimOf
import com.varabyte.kotter.foundation.input.Keys
import com.varabyte.kotter.foundation.input.onInputEntered
import com.varabyte.kotter.foundation.input.onKeyPressed
import com.varabyte.kotter.foundation.input.runUntilKeyPressed
import com.varabyte.kotter.foundation.session
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.render.RenderScope
import model.Profile
import model.ship.ShipRole
import screen.BootScreen
import screen.RunningScreen
import screen.Screen
import java.awt.Color
import kotlin.math.round
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

enum class AppState(val screen: Screen) {
    BOOT(BootScreen()),
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
var appState = BOOT

val HEADER_COLOR = Color(149, 149, 240)
val SELECTED_HEADER_COLOR = Color(26, 208, 222)
val BROWN_EXCAVATOR_COLOR = Color(120, 80, 40)
val bootContext = BootRenderContext()
val runningRenderContext = RunningRenderContext()

val notificationBlink = TextAnim.Template(listOf(" + ", " - "), 500.milliseconds)

var showSystemScreen = false

fun isActiveScreen(screen: Screen): Boolean = screen == appState.screen
fun getActiveAppState(): AppState = appState
suspend fun main() {

    // The below needs to come from the profile.settings.json file
    session {
        runningRenderContext.textAnim = textAnimOf(notificationBlink)
        section {
            with(appState.screen) {
                render()
            }
        }.runUntilKeyPressed(Keys.ESC) {
            val runScope = this
            onInputEntered {
                with(appState.screen) {
                    appState = onInput(runScope)
                }
            }
            onKeyPressed {
                with(appState.screen) {
                    appState = onKeyPressed(runScope)
                }
            }
        }
    }
    SpaceTradersClient.client.close()
}

fun RenderScope.makeHeader(text: String, columns: Int) {
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
    var textAnim: TextAnim? = null
)
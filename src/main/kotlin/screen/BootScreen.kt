package screen

import AppState
import AppState.BOOT
import AppState.RUNNING
import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.render.aside
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import getActiveAppState
import io.github.oshai.kotlinlogging.KotlinLogging
import isActiveScreen
import kotlinx.coroutines.runBlocking
import model.exceptions.BootFailure
import startup.BootManager

private val logger = KotlinLogging.logger {}

class BootScreen(var userAskedNew: Boolean = false) : Screen() {

    private val self = this

    override fun MainRenderScope.render() {
        textLine("Start - Load the agent whose token is in profile/authtoken.secret")
        textLine("New - Register a new agent (needs an account token in profile/accounttoken.secret)")
        textLine("Debug - Same as Start while automation is disabled")
        textLine("Esc - Quit")
        textLine()
        textLine("Ship automation is switched off pending the scripting overhaul.")
        text("> ")
        input()
    }

    override fun OnInputEnteredScope.onInput(runScope: RunScope): AppState {
        if (!isActiveScreen(self)) return getActiveAppState()

        val command = input.trim().uppercase()
        clearInput()
        return when (command) {
            "NEW" -> {
                if (!self.userAskedNew) {
                    self.userAskedNew = true
                    runScope.aside {
                        yellow { textLine("This wipes the local profile data and registers a new agent. Type NEW again to confirm.") }
                    }
                    BOOT
                } else {
                    self.userAskedNew = false
                    attempt(runScope, "Registering a new agent") { runBlocking { BootManager.bootstrapNew() } }
                }
            }

            "START" -> {
                self.userAskedNew = false
                attempt(runScope, "Loading agent") { BootManager.normalStart() }
            }

            "DEBUG" -> {
                self.userAskedNew = false
                attempt(runScope, "Debug start") { runBlocking { BootManager.debugStart() } }
            }

            "" -> BOOT

            else -> {
                self.userAskedNew = false
                runScope.aside { textLine("Unknown command '$command'. Type New, Start or Debug, or press Esc to quit.") }
                BOOT
            }
        }
    }

    override fun OnKeyPressedScope.onKeyPressed(runScope: RunScope): AppState {
        return getActiveAppState()
    }

    /**
     * Runs a boot action. On success the app moves to the running screen; on failure the reason is
     * logged, printed above the menu, and the menu stays up so the user can fix the cause and retry.
     */
    private fun attempt(runScope: RunScope, what: String, action: () -> Unit): AppState {
        logger.info { what }
        return try {
            action()
            RUNNING
        } catch (e: BootFailure) {
            logger.error { "$what failed: ${e.message}" }
            runScope.aside { red { textLine("$what failed: ${e.message}") } }
            BOOT
        } catch (e: Exception) {
            logger.error(e) { "$what failed" }
            runScope.aside {
                red { textLine("$what failed: ${e::class.simpleName}: ${e.message}. Details are in log.txt.") }
            }
            BOOT
        }
    }
}

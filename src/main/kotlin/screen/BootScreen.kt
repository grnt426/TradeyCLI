package screen

import AppState
import AppState.BOOT
import AppState.LOADING
import AppState.RUNNING
import appState
import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.render.aside
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.foundation.text.yellow
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import getActiveAppState
import io.github.oshai.kotlinlogging.KotlinLogging
import isActiveScreen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import model.BootProgress
import app.App
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
                    launchBoot("Registering a new agent") { BootManager.bootstrapNew() }
                }
            }

            "START" -> {
                self.userAskedNew = false
                launchBoot("Loading agent") { BootManager.normalStart() }
            }

            "DEBUG" -> {
                self.userAskedNew = false
                launchBoot("Debug start") { BootManager.debugStart() }
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
     * Runs a boot action on the engine scope and switches to the loading screen straight away, so
     * the terminal keeps rendering while the API calls run. On success the app moves to the
     * running screen; on failure the loading screen shows the reason and offers the menu again.
     */
    private fun launchBoot(what: String, action: suspend () -> Unit): AppState {
        logger.info { what }
        BootProgress.reset(what)
        App.engine.scope.launch {
            try {
                action()
                BootProgress.finish()
                appState = RUNNING
            } catch (e: CancellationException) {
                throw e
            } catch (e: BootFailure) {
                logger.error { "$what failed: ${e.message}" }
                BootProgress.fail("$what failed: ${e.message}")
            } catch (e: Exception) {
                logger.error(e) { "$what failed" }
                BootProgress.fail("$what failed: ${e::class.simpleName}: ${e.message}. Details are in log.txt.")
            }
        }
        return LOADING
    }
}

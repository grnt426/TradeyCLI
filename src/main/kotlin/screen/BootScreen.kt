package screen

import AppState
import AppState.BOOT
import AppState.RUNNING
import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.render.aside
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import getActiveAppState
import io.github.oshai.kotlinlogging.KotlinLogging
import isActiveScreen
import kotlinx.coroutines.runBlocking
import startup.BootManager
import kotlin.system.exitProcess

private val logger = KotlinLogging.logger {}
class BootScreen(var userAskedNew: Boolean = false) : Screen() {

    private val self = this
    override fun MainRenderScope.render() {
        logger.info { "Hello, World!" }
        logger.error { "Hello, World!" }
        textLine("New - Create a new Agent")
        textLine("Start - Load everything and start all scripts")
        textLine("Debug - Load data, but don't run scripts")
        text("> ")
        input()
    }

    override fun OnInputEnteredScope.onInput(runScope: RunScope): AppState {
        if (isActiveScreen(self)) {
            when (input.uppercase()) {
                "NEW" -> {
                    return if (self.userAskedNew) {
                        clearInput()
                        println("NEW AGENT")
                        runBlocking { BootManager.bootstrapNew() }
                        RUNNING
                    } else {
                        clearInput()
                        runScope.aside { textLine("Type NEW again to confirm") }
                        self.userAskedNew = true
                        BOOT
                    }
                }

                "START" -> {
                    clearInput()
                    println("NORMAL START")
                    BootManager.normalStart()
                    return RUNNING
                }

                "DEBUG" -> {
                    clearInput()
                    println("DEBUG START")
                    runBlocking { BootManager.debugStart() }
                    return RUNNING
                }

                else -> {
                    println("Exiting")
                    exitProcess(1)
                }
            }
        } else {
            return getActiveAppState()
        }
    }

    override fun OnKeyPressedScope.onKeyPressed(runScope: RunScope): AppState {
        return getActiveAppState()
    }
}
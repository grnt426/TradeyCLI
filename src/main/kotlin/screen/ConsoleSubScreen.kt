package screen

import ACTIONS
import HEADER_COLOR
import QuadSelect
import SELECTED_HEADER_COLOR
import Window
import applyShipRoleColor
import client.SpaceTradersClient
import com.varabyte.kotter.foundation.anim.text
import com.varabyte.kotter.foundation.input.*
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import com.varabyte.kotterx.grid.Cols
import com.varabyte.kotterx.grid.grid
import commandHistory
import commandHistoryIndex
import data.FileWritingQueue
import makeHeader
import model.GameState
import model.GameState.getHqSystem
import model.ship.ShipNavStatus
import model.ship.calculateExpirationSeconds
import model.ship.components.Inventory
import model.ship.components.shortName
import model.ship.getShips
import model.ship.hasCooldown
import notification.NotificationManager
import reduceToSiNotation
import runningRenderContext
import screen.RunningScreen.SelectedScreen
import script.ScriptExecutor
import java.time.Instant
import kotlin.math.max
import kotlin.math.min

class ConsoleSubScreen(private val parent: Screen) : SubScreen<SelectedScreen>(parent) {
    val self = this
    val columns = 3
    override fun MainRenderScope.render() {
        val selectedQuad = runningRenderContext.selectedQuad
        val currentView = runningRenderContext.selectedView
        val selectedShip = runningRenderContext.selectedShip

        grid(Cols.uniform(columns, GameState.profData.termWidth / columns)) {
            cell {
                val headerColor = if (selectedQuad != QuadSelect.MAIN) HEADER_COLOR else SELECTED_HEADER_COLOR
                when (currentView) {
                    Window.MAIN -> {
                        rgb(headerColor.rgb) {
                            makeHeader("Fleet Status: ${getHqSystem().symbol}", columns)
                        }

                        getShips().forEach { s ->
                            text("${shortName(s)} [")
                            applyShipRoleColor(s.registration.role)
                            text("] ${s.script?.currentState}")
                            if (s.cooldown.remainingSeconds > 0)
                                textLine(" (${s.cooldown.remainingSeconds})")
                            else if (s.nav.status == ShipNavStatus.IN_TRANSIT) {
                                val arrival = s.nav.route.arrival
                                val secondsRemaining = arrival.epochSecond.minus(Instant.now().epochSecond).toDouble()
                                textLine(" (${reduceToSiNotation(secondsRemaining, "s")})")
                                textLine(" Destination @ ${s.nav.route.destination.symbol}")
                            } else
                                textLine()
                            if (s.cargo.inventory.isNotEmpty()) {
                                text(" * ")
                                textLine(s.cargo.inventory.chunked(2).joinToString("\n * ", transform = { chunk ->
                                    chunk.joinToString(", ", transform = { c: Inventory ->
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
                    makeHeader("Agent", columns)
                }
                textLine("${GameState.agent.symbol} - $${GameState.agent.credits}")
                textLine("${GameState.ships.size} ships and ${GameState.scriptsRunning.size} scripts")
            }

            cell {
                val blocks = arrayOf(" ", "▁", "▂", "▃", "▄", "▅", "▆", "▇", "█")
                val FULL_BLOCK = blocks[8]
                rgb(HEADER_COLOR.rgb) {
                    makeHeader("Job Pressure", columns)
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
                    makeHeader("Command", columns)
                }
                val ship = getShips()[selectedShip - 1]
                text("${ship.registration.name}#")
                applyShipRoleColor(ship.registration.role, false)
                textLine(" ${ship.nav.status}@${ship.nav.waypointSymbol}")
                text(" C: ${ship.cargo.units}/${ship.cargo.capacity} F: ${ship.fuel.current}/${ship.fuel.capacity} ")
                textLine(if (ship.script != null) "${ship.script!!.currentState}" else "NS")
                if (hasCooldown(ship)) {
                    val expire = if (ship.cooldown.remainingSeconds > 0) {
                        ship.cooldown.remainingSeconds
                    } else {
                        calculateExpirationSeconds(ship)
                    }
                    textLine("Cool: ${reduceToSiNotation(expire.toDouble(), "s")}")
                }
            }

            cell {

                val headerColor = if (selectedQuad != QuadSelect.NOTF) HEADER_COLOR else SELECTED_HEADER_COLOR
                rgb(headerColor.rgb) {
                    makeHeader("Notifications", columns)
                }
                NotificationManager.notifications.forEach { n ->
                    rgb(n.animColor.rgb) {
                        text(n.textAnim)
                    }
                    textLine(n.toast)
                }
            }

            cell {
                rgb(HEADER_COLOR.rgb) { makeHeader("Console Stats", columns) }
                val writes = FileWritingQueue.totalFileWrites
                val states = ScriptExecutor.totalStateChanges
                val errors = SpaceTradersClient.totalErrors
                textLine("Writes $writes | Errors $errors | State => $states")
            }
        }
        text("> ")
        input(Completions(*ACTIONS.toTypedArray()))
    }

    override fun OnInputEnteredScope.onInput(runScope: RunScope): SelectedScreen {
        if (parent.isActiveSubScreen(self) && runningRenderContext.selectedQuad == QuadSelect.NONE) {
            commandHistoryIndex = 0
            commandHistory.addFirst(input)
            val args = input.split(" ").map { str -> str.uppercase() }
            val command = args[0]
            when (args.size) {
                1 -> {
                    when (command) {
                        "CONTRACTS" -> runningRenderContext.selectedView = Window.CONTRACT
                        "SYSTEM" -> {
                            this.clearInput()
                            return SelectedScreen.SYSTEM
                        }

                        "CONSOLE" -> {
                            this.clearInput()
                            return SelectedScreen.CONSOLE
                        }
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
            return parent.getActiveSelectedScreen() as SelectedScreen
        } else {
            return parent.getActiveSelectedScreen() as SelectedScreen
        }
    }

    override fun OnKeyPressedScope.onKeyPressed(runScope: RunScope): SelectedScreen {
        if (parent.isActiveSubScreen(self)) {
            println("Pressed: $key ${key.hashCode()}")
            when (key) {
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
                    if (commandHistory.size > 0 && commandHistoryIndex < commandHistory.size) {
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

            return SelectedScreen.CONSOLE
        } else {
            return parent.getActiveSelectedScreen() as SelectedScreen
        }
    }
}
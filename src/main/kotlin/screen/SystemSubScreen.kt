package screen

import ACTIONS
import HEADER_COLOR
import com.varabyte.kotter.foundation.input.*
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import commandHistory
import commandHistoryIndex
import model.GameState
import model.Profile
import model.system.WaypointType
import runningRenderContext
import screen.RunningScreen.SelectedScreen
import kotlin.math.roundToInt

class SystemSubScreen(private val parent: Screen) : SubScreen<SelectedScreen>(parent) {
    private val self = this
    private var zoom = Point(1.0, 1.0)
    private var translate = Point(0.0, 0.0)

    // fonts are taller than wide, so an aspect ratio is needed of 3:5
    private val aspectRatio = Point(3.0, 5.0)
    override fun MainRenderScope.render() {

        val wp = GameState.waypoints.values
        val centerVector = Point(35.0, 20.0) + translate

        // a good starting zoom is 10
        val zoom = (Point(10.0, 10.0) + self.zoom) * aspectRatio

        // initialize empty grid
        val rows = 40
        val cols = 75
        val map = Array(rows) { // number of rows
            Array(cols) { // number of columns
                " "
            }
        }

        // put a star in the center
        map[centerVector.y.toInt()][centerVector.x.toInt()] = "S"
        val waypointNames = mutableListOf<() -> Unit>()

        wp.forEach { w ->
            val pos = (Point(w.x.toDouble(), w.y.toDouble()) / zoom) + centerVector
            val y = pos.y.roundToInt()
            val x = pos.x.roundToInt()

            // cull out of bounds objects
            if (x in 0..<cols && y in 0..<rows) {
                waypointNames.add {
                    text("${w.symbol} - ")
                    rgb(w.type.color.rgb) { text(w.type.name) }
                    text(" @ ${w.x},${w.y}")
                }
                map[y][x] = w.type.name[0].toString()
            }
        }

        repeat(cols + 2) { text("_") }
        text(" ")
        rgb(HEADER_COLOR.rgb) {
            underline {
                val header = "Waypoints in ${wp.first().systemSymbol}"
                text(header)
                repeat(Profile.profileData.termWidth - header.length - 75) { text(" ") }
            }
        }
        textLine()
        applyEffects(map, waypointNames)
        repeat(cols + 2) { text("-") }
        textLine()
        text("> ")
        input(Completions(*ACTIONS.toTypedArray()))
    }

    override fun OnInputEnteredScope.onInput(runScope: RunScope): SelectedScreen {
        if (parent.isActiveSubScreen(self)) {
            commandHistoryIndex = 0
            commandHistory.addFirst(input)
            val args = input.split(" ").map { str -> str.uppercase() }
            val command = args[0]
            when (args.size) {
                1 -> {
                    when (command) {
                        "SYSTEM" -> {
                            this.clearInput()
                            return SelectedScreen.SYSTEM
                        }

                        "CONSOLE" -> {
                            this.clearInput()
                            return SelectedScreen.CONSOLE
                        }
                    }
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
            runScope.setInput("")
            when (key) {
                Keys.PLUS -> {
                    zoom -= Point(1.0, 1.0)
                }

                Keys.MINUS -> {
                    zoom += Point(1.0, 1.0)
                }

                Keys.LEFT -> {
                    translate -= Point(1.0, 0.0)
                }

                Keys.RIGHT -> {
                    translate += Point(1.0, 0.0)
                }

                Keys.UP -> {
                    translate -= Point(0.0, 1.0)
                }

                Keys.DOWN -> {
                    translate += Point(0.0, 1.0)
                }
            }
            return SelectedScreen.SYSTEM
        }
        return parent.getActiveSelectedScreen() as SelectedScreen
    }

    private fun MainRenderScope.applyEffects(map: Array<Array<String>>, waypointNames: MutableList<() -> Unit>) {
        map.forEachIndexed { index, row ->
            text("|")
            row.forEach { c ->
                when (c) {
                    "A" -> {
                        rgb(WaypointType.ASTEROID.color.rgb) {
                            text(c)
                        }
                    }

                    "J" -> {
                        magenta {
                            text(c)
                        }
                    }

                    "P" -> {
                        green {
                            text(c)
                        }
                    }

                    "F" -> {
                        yellow {
                            text(c)
                        }
                    }

                    "O" -> {
                        blue {
                            text(c)
                        }
                    }

                    "S" -> {
                        red {
                            text(c)
                        }
                    }

                    "M" -> {
                        rgb(WaypointType.MOON.color.rgb) {
                            text(c)
                        }
                    }

                    else -> {
                        text(c)
                    }
                }
            }
            if (waypointNames.size > index) {
                text("| ")
                waypointNames[index]()
                textLine()
            } else
                textLine("|")
        }
    }

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
}
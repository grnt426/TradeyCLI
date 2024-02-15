package screen

import ACTIONS
import HEADER_COLOR
import com.varabyte.kotter.foundation.input.*
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import model.GameState
import model.Profile
import screen.RunningScreen.SelectedScreen
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SystemSubScreen(private val parent: Screen) : SubScreen<SelectedScreen>(parent) {
    private val self = this
    private var zoom = Point(1.0, 1.0)
    private var translate = Point(0.0, 0.0)
    private var selectIndex = 0
    private var objectsOnScreen = 0

    // fonts are taller than wide, so an aspect ratio is needed of 3:5
    private val aspectRatio = Point(3.0, 5.0)
    override fun MainRenderScope.render() {
        selectIndex = min(selectIndex, objectsOnScreen)
        objectsOnScreen = 0
        val wp = GameState.waypoints.values
        val centerVector = Point(35.0, 20.0) + translate

        // a good starting zoom is 10
        val zoom = (Point(10.0, 10.0) + self.zoom) * aspectRatio

        // initialize empty grid
        val rows = 40
        val cols = 75
        val EMPTY_RENDER = { text(" ") }
        val map = MutableList(rows) { // number of rows
            MutableList(cols) { // number of columns
                EMPTY_RENDER
            }
        }

        // put a star in the center
        map[centerVector.y.toInt()][centerVector.x.toInt()] = { red { text("S") } }
        val waypointNames = mutableListOf<() -> Unit>()

        wp.forEach { w ->
            val pos = (Point(w.x.toDouble(), w.y.toDouble()) / zoom) + centerVector
            val y = pos.y.roundToInt()
            val x = pos.x.roundToInt()

            // cull out of bounds objects
            if (x in 0..<cols && y in 0..<rows) {
                objectsOnScreen++
                val selected = waypointNames.size == selectIndex
                waypointNames.add {
                    val entry = {
                        text("${w.symbol} - ")
                        rgb(w.type.color.rgb) { text(w.type.name) }
                        text(" @ ${w.x},${w.y}")
                    }
                    if (selected) {
                        bold {
                            underline {
                                entry()
                            }
                        }
                    } else {
                        entry()
                    }
                }
                map[y][x] = {
                    val entry = {
                        rgb(w.type.color.rgb) {
                            text(w.type.name[0].toString())
                        }
                    }
                    if (selected) {
                        white(layer = ColorLayer.BG) {
                            entry()
                        }
                    } else {
                        entry()
                    }
                }
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
        return parent.getActiveSelectedScreen() as SelectedScreen
    }

    override fun OnKeyPressedScope.onKeyPressed(runScope: RunScope): SelectedScreen {
        if (parent.isActiveSubScreen(self)) {
            runScope.setInput("")
            when (key) {
                Keys.PLUS -> zoom -= Point(0.5, 0.5)
                Keys.MINUS -> zoom += Point(0.5, 0.5)
                Keys.LEFT -> translate -= Point(0.5, 0.5)
                Keys.RIGHT -> translate += Point(0.5, 0.5)
                Keys.UP -> translate -= Point(0.0, 1.0)
                Keys.DOWN -> translate += Point(0.0, 1.0)
                Keys.PAGE_UP -> selectIndex = max(0, selectIndex - 1)
                Keys.PAGE_DOWN -> selectIndex = min(objectsOnScreen, selectIndex + 1)
                Keys.TICK -> {
                    return SelectedScreen.CONSOLE
                }
            }
            runScope.rerender()
            return SelectedScreen.SYSTEM
        }
        return parent.getActiveSelectedScreen() as SelectedScreen
    }

    private fun MainRenderScope.applyEffects(
        map: MutableList<MutableList<() -> Unit>>,
        waypointNames: MutableList<() -> Unit>
    ) {
        map.forEachIndexed { index, row ->
            text("|")
            row.forEach { c ->
                c()
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
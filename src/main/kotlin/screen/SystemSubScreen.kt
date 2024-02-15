package screen

import ACTIONS
import Window
import com.varabyte.kotter.foundation.input.Completions
import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import commandHistory
import commandHistoryIndex
import model.GameState
import runningRenderContext
import screen.RunningScreen.SelectedScreen
import kotlin.math.roundToInt

class SystemSubScreen(private val parent: Screen) : SubScreen<SelectedScreen>(parent) {
    private val self = this
    override fun MainRenderScope.render() {
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
        val centerVector = Point(35.0, 20.0)

        // fonts are taller than wide, so an aspect ratio is needed of 3:5
        // we divide by a factor of 10 of that ratio to shrink the large coordinates
        // to something that fits a console screen
        val normalize = Point(30.0, 50.0)

        // initialize empty grid
        val rows = 40
        val cols = 75
        val map = Array(rows) { // number of rows
            Array(cols) { // number of columns
                " "
            }
        }

        wp.forEach { w ->
            val pos = (Point(w.x.toDouble(), w.y.toDouble()) / normalize) + centerVector

            // guard to prevent index out of bounds
            if (pos.x in 0.0..cols.toDouble() && pos.y in 0.0..rows.toDouble())
                map[pos.y.roundToInt()][pos.x.roundToInt()] = w.type.name[0].toString()
        }

        repeat(cols + 2) { text("_") }
        textLine()
        map.forEach { row ->
            textLine("|${row.joinToString("")}|")
        }
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
                        "CONTRACTS" -> runningRenderContext.selectedView = Window.CONTRACT
                        "SYSTEM" -> return SelectedScreen.SYSTEM
                        "CONSOLE" -> return SelectedScreen.CONSOLE
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
            return SelectedScreen.SYSTEM
        }
        return parent.getActiveSelectedScreen() as SelectedScreen
    }

}
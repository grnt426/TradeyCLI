package screen

import ACTIONS
import HEADER_COLOR
import QuadSelect
import SELECTED_HEADER_COLOR
import Window
import applyShipRoleColor
import api.JobPressure
import api.pressureOf
import com.varabyte.kotter.foundation.anim.text
import com.varabyte.kotter.foundation.input.*
import com.varabyte.kotter.foundation.text.*
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import com.varabyte.kotter.runtime.render.OffscreenRenderScope
import com.varabyte.kotter.runtime.render.RenderScope
import com.varabyte.kotterx.grid.Cols
import com.varabyte.kotterx.grid.GridCharacters
import com.varabyte.kotterx.grid.grid
import commandHistory
import commandHistoryIndex
import io.github.oshai.kotlinlogging.KotlinLogging
import makeHeader
import app.App
import behaviour.decisions.CreditsTrend
import behaviour.decisions.Intent
import behaviour.decisions.Intentions
import behaviour.decisions.Summary
import model.market.Market
import model.ship.ShipNavStatus
import model.ship.components.Inventory
import model.system.Waypoint
import notification.NotificationManager
import reduceToSiNotation
import runningRenderContext
import screen.RunningScreen.SelectedScreen
import java.awt.Color
import java.time.Instant
import kotlin.math.*
import kotlin.random.Random


class ConsoleSubScreen(private val parent: Screen) : SubScreen<SelectedScreen>(parent) {

    private val logger = KotlinLogging.logger {}

    companion object {
        const val COLUMNS = 8
        const val GRAPH_ROWS = 6
        private val BLOCKS = listOf(" ", "▁", "▂", "▃", "▄", "▅", "▆", "▇", "█")
    }

    override fun MainRenderScope.render() {
        val snap = App.engine.state.value
        val now = Instant.now()
        val selectedQuad = runningRenderContext.selectedQuad
        val columnWidth = App.profData.termWidth / COLUMNS
        val trend = CreditsTrend.trend(snap.creditsHistory, now)
        val progress = Summary.progress(snap, now, trend)

        // Every panel prints a bounded number of lines, each cut to its cell's width, so the
        // screen never grows however long the run gets.
        fun RenderScope.line(text: String, width: Int, tone: Intent.Tone = Intent.Tone.NEUTRAL) {
            val shown = text.take(width - 1)
            when (tone) {
                Intent.Tone.GOOD -> green { textLine(shown) }
                Intent.Tone.WARN -> yellow { textLine(shown) }
                Intent.Tone.NEUTRAL -> textLine(shown)
            }
        }
        fun RenderScope.wrapped(text: String, width: Int, tone: Intent.Tone, maxLines: Int) {
            wrap(text, width - 1).take(maxLines).forEachIndexed { i, l -> line(if (i == 0) l else "  $l", width, tone) }
        }

        grid(Cols.uniform(COLUMNS, columnWidth), characters = GridCharacters.Curved) {
            // ROW 1: who and where we stand, the bank, the markets
            cell(colSpan = 3) {
                val w = columnWidth * 3
                val phase = snap.plan?.phase ?: plan.Phase.ESCAPE
                rgb(HEADER_COLOR.rgb) { makeHeader("${snap.agent?.symbol ?: "?"}  ${phase.name}", 3) }
                wrapped(progress.headline, w, Intent.Tone.GOOD, 2)
                progress.lines.take(4).forEach { wrapped(it.text, w, it.tone, 2) }
                val runner = snap.runner
                val driver = when {
                    runner == null -> Intent("nobody is running the plan; start `TradeyCLI run`", Intent.Tone.WARN)
                    runner.isLive(now, Intentions.processAlive) -> Intent("driven by process ${runner.pid}, heartbeat ${java.time.Duration.between(runner.heartbeat, now).seconds}s ago", Intent.Tone.GOOD)
                    else -> Intent("run process ${runner.pid} is gone; nothing is driving the plan", Intent.Tone.WARN)
                }
                line(driver.text, w, driver.tone)
            }
            cell(colSpan = 3) {
                rgb(HEADER_COLOR.rgb) { makeHeader("Credits", 3) }
                creditsGraph(snap, columnWidth * 3)
            }
            cell(colSpan = 2) {
                val w = columnWidth * 2
                rgb(HEADER_COLOR.rgb) { makeHeader("Market health", 2) }
                val health = Summary.marketHealth(snap, now)
                if (health.isEmpty()) line("no prices read yet", w)
                health.take(6).forEach { h ->
                    val tone = when { h.score >= 0.6 -> Intent.Tone.GOOD; h.score >= 0.4 -> Intent.Tone.NEUTRAL; else -> Intent.Tone.WARN }
                    line("${h.system} ${(h.score * 100).toInt()}% of ${h.markets} mkts", w, tone)
                    line("  ${h.restrictedExports} restricted, ${h.scarce} scarce, ${h.saturatedImports} buried" + (h.oldestReadHours?.let { if (it >= 3) ", oldest %.0fh".format(it) else "" } ?: ""), w)
                }
            }

            // ROW 2: the fleet and the money
            cell(colSpan = 4) {
                val w = columnWidth * 4
                val headerColor = if (selectedQuad != QuadSelect.MAIN) HEADER_COLOR else SELECTED_HEADER_COLOR
                rgb(headerColor.rgb) { makeHeader("Fleet (${snap.ships.size})", 4) }
                Summary.fleet(snap, now).take(14).forEach { r ->
                    val cargo = if (r.cargo.isBlank()) "" else " [${r.cargo}]"
                    line("${r.ship.padStart(2)} ${r.type.take(9).padEnd(9)} ${r.behaviour}: ${r.phase} ${r.detail}".take(w - cargo.length - r.where.length - 3).padEnd(w - cargo.length - r.where.length - 3) + " ${r.where}$cargo", w, r.tone)
                }
            }
            cell(colSpan = 2) {
                val w = columnWidth * 2
                rgb(HEADER_COLOR.rgb) { makeHeader("Spent on (24h)", 2) }
                val spending = Summary.spending(snap, now.minus(Summary.WINDOW))
                if (spending.isEmpty()) line("nothing yet", w)
                spending.take(7).forEach { f -> line("${f.category.padEnd(14)} ${CreditsTrend.compact(f.credits).padStart(7)} ${(f.share * 100).toInt().toString().padStart(3)}%", w) }
            }
            cell(colSpan = 2) {
                val w = columnWidth * 2
                rgb(HEADER_COLOR.rgb) { makeHeader("Earned from (24h)", 2) }
                val revenue = Summary.revenue(snap, now.minus(Summary.WINDOW))
                if (revenue.isEmpty()) line("nothing yet", w)
                revenue.take(7).forEach { f -> line("${f.category.padEnd(14)} ${CreditsTrend.compact(f.credits).padStart(7)} ${(f.share * 100).toInt().toString().padStart(3)}%", w) }
            }

            // ROW 3: what the bot says about itself, notices, the API
            cell(colSpan = 4) {
                val w = columnWidth * 4
                rgb(HEADER_COLOR.rgb) { makeHeader("Plan", 4) }
                val ships = snap.plan?.assignments?.map { it.ship }?.toSet() ?: emptySet()
                Intentions.describe(snap, now, trend)
                    .filter { intent -> ships.none { intent.text.startsWith(it) } && !intent.text.startsWith("Plan driven") && !intent.text.startsWith("Run process") && !intent.text.startsWith("Nobody") }
                    .take(6)
                    .forEach { wrapped(it.text, w, it.tone, 2) }
                snap.plan?.chains?.take(2)?.forEach { c -> line("chain ${c.id}: ${c.ships.size} ships, ${c.legs.size} legs" + (if (c.hold) ", held" else ""), w) }
            }
            cell(colSpan = 2) {
                val w = columnWidth * 2
                val headerColor = if (selectedQuad != QuadSelect.NOTF) HEADER_COLOR else SELECTED_HEADER_COLOR
                rgb(headerColor.rgb) { makeHeader("Notifications", 2) }
                NotificationManager.notifications.takeLast(5).forEach { n ->
                    rgb(n.animColor.rgb) { text(n.textAnim) }
                    textLine(n.toast.take(w - 3))
                }
            }
            cell(colSpan = 2) {
                val w = columnWidth * 2
                rgb(HEADER_COLOR.rgb) { makeHeader("API", 2) }
                val stats = App.engine.apiClient?.stats
                line("requests ${stats?.requests?.get() ?: 0}  errors ${stats?.errors?.get() ?: 0}  throttled ${stats?.throttled?.get() ?: 0}", w)
                val pressure = App.engine.pacer.queueHistory.takeLast(w - 8)
                text("queue  ")
                pressure.forEach { q ->
                    val glyph = BLOCKS[min(q, 8)]
                    when (pressureOf(q)) {
                        JobPressure.LOW -> green { text(glyph) }
                        JobPressure.OK -> yellow { text(glyph) }
                        JobPressure.HIGH -> red { text(glyph) }
                    }
                }
                textLine()
                line("`summary`, `race`, `gate` in line mode print these as tables", w)
            }
        }
        text("> ")
        input(Completions(*ACTIONS.toTypedArray()))
    }

    override fun OnInputEnteredScope.onInput(runScope: RunScope): SelectedScreen {
        if (parent.isActiveSubScreen(this@ConsoleSubScreen) && runningRenderContext.selectedQuad == QuadSelect.NONE) {
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

                        "MARKET" -> {
                            this.clearInput()
                            return SelectedScreen.MARKET
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
        if (parent.isActiveSubScreen(this@ConsoleSubScreen)) {
            logger.info { "Got Key Press" }
            println("Pressed: $key ${key.hashCode()}")
            when (key) {
                Keys.Digit1 -> {
                    val inputLen = (runScope.getInput()?.length ?: 0)
                    println("Input len $inputLen")
                    if (inputLen <= 1) {
                        runningRenderContext.selectedQuad = QuadSelect.MAIN
                        runScope.setInput("")
                    }
                }

                Keys.Digit2 -> {
                    val inputLen = (runScope.getInput()?.length ?: 0)
                    println("Input len $inputLen")
                    if (inputLen <= 1) {
                        runningRenderContext.selectedQuad = QuadSelect.SUMM
                        runScope.setInput("")
                    }
                }

                Keys.Digit3 -> {
                    val inputLen = (runScope.getInput()?.length ?: 0)
                    println("Input len $inputLen")
                    if (inputLen <= 1) {
                        runningRenderContext.selectedQuad = QuadSelect.COMM
                        runScope.setInput("")
                    }
                }

                Keys.Digit4 -> {
                    val inputLen = (runScope.getInput()?.length ?: 0)
                    println("Input len $inputLen")
                    if (inputLen <= 1) {
                        runningRenderContext.selectedQuad = QuadSelect.NOTF
                        runScope.setInput("")
                    }
                }

                // Doesn't quite work...
                Keys.Up -> {
                    if (commandHistory.size > 0 && commandHistoryIndex < commandHistory.size) {
                        runScope.setInput(commandHistory[commandHistoryIndex])
                        commandHistoryIndex = min(commandHistory.size - 1, commandHistoryIndex++)
                    }
                }

                Keys.Down -> {
                    if (commandHistory.size > 0 && commandHistoryIndex >= 0) {
                        runScope.setInput(commandHistory[commandHistoryIndex])
                        commandHistoryIndex = max(0, commandHistoryIndex--)
                    }
                }

                Keys.Tab -> {
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

    /** Word-wraps [text] to lines of at most [width] characters; continuation lines are indented by the caller. */
    private fun wrap(text: String, width: Int): List<String> {
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        text.split(' ').forEach { word ->
            val limit = if (lines.isEmpty()) width else width - 2
            if (current.isNotEmpty() && current.length + 1 + word.length > limit) {
                lines += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word.take(limit))
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /**
     * The bank over the last hour as bars, then the trend's projection in yellow. Every row is
     * exactly [width] characters so the grid stays aligned.
     */
    // RenderScope, not MainRenderScope: a grid cell renders into an offscreen scope, and an
    // extension on the main scope would paint at the top of the screen instead of in the cell.
    private fun RenderScope.creditsGraph(snap: engine.Snapshot, width: Int) {
        val labelWidth = 8
        val columns = (width - labelWidth - 1).coerceAtLeast(20)
        val projection = columns / 3
        val graph = CreditsTrend.graph(snap.creditsHistory, Instant.now(), historyColumns = columns - projection, projectionColumns = projection)
        val rows = GRAPH_ROWS
        if (snap.creditsHistory.isEmpty()) {
            textLine("No credits history yet; it fills as the bot trades.")
            return
        }
        // Rows top to bottom; each row is a band of values, so a bar is full below its top and partial at it.
        for (row in 0 until rows) {
            text(graph.label(row, rows).padStart(labelWidth - 1) + " ")
            graph.columns.forEach { column ->
                val value = column.value
                val glyph = if (value == null) " " else BLOCKS[graph.glyphIndex(value, row, rows)]
                if (column.projected) yellow { text(glyph) } else green { text(glyph) }
            }
            textLine()
        }
        textLine(" ".repeat(labelWidth) + graph.axis())
        textLine(" ".repeat(labelWidth) + graph.axisLabels())
        val trend = graph.trend
        val now = snap.agent?.credits ?: 0
        val ahead = graph.projectionSpan.toMinutes()
        textLine("now ${Intentions.format(now)}   ${if (trend.perHour >= 0) "+" else ""}${Intentions.format(trend.perHour.toLong())}/h   in ${ahead}m: ~${Intentions.format(graph.projectedEnd.toLong())}".take(width - 1))
    }
}

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
import com.varabyte.kotter.runtime.render.OffscreenRenderScope
import com.varabyte.kotterx.grid.Cols
import com.varabyte.kotterx.grid.GridCharacters
import com.varabyte.kotterx.grid.grid
import commandHistory
import commandHistoryIndex
import data.FileWritingQueue
import makeHeader
import model.GameState
import model.GameState.getHqSystem
import model.market.Market
import model.ship.ShipNavStatus
import model.ship.calculateExpirationSeconds
import model.ship.components.Inventory
import model.ship.components.shortName
import model.ship.getShips
import model.ship.hasCooldown
import model.system.Waypoint
import notification.NotificationManager
import reduceToSiNotation
import runningRenderContext
import screen.RunningScreen.SelectedScreen
import script.ScriptExecutor
import java.awt.Color
import java.time.Instant
import kotlin.math.*
import kotlin.random.Random

class ConsoleSubScreen(private val parent: Screen) : SubScreen<SelectedScreen>(parent) {
    val self = this

    companion object {
        val columns = 8
    }

    private var zoom = SystemSubScreen.Point(1.0, 1.0)
    private var translate = SystemSubScreen.Point(0.0, 0.0)
    private var selectIndex = 0
    private var objectsOnScreen = 0

    // fonts are taller than wide, so an aspect ratio is needed of 3:5
    private val aspectRatio = SystemSubScreen.Point(3.0, 5.0)

    override fun MainRenderScope.render() {
        val selectedQuad = runningRenderContext.selectedQuad
        val currentView = runningRenderContext.selectedView
        val selectedShip = runningRenderContext.selectedShip
        var selectedWaypoint: Waypoint? = null
        var selectedMarket: Market? = null

        grid(Cols.uniform(columns, GameState.profData.termWidth / columns), characters = GridCharacters.CURVED) {
            val visibleWaypoints = mutableListOf<Waypoint>()
            selectIndex = min(selectIndex, objectsOnScreen)
            cell(colSpan = 3, rowSpan = 4) {
                rgb(HEADER_COLOR.rgb) { makeHeader("System View", 3) }
                objectsOnScreen = 0
                val wp = GameState.waypoints.values

                // a good starting zoom is 10
                val zoom = (SystemSubScreen.Point(3.0, 3.0) + self.zoom) * aspectRatio

                // initialize empty grid
                val rows = 30
                val cols = 60
                val centerVector = SystemSubScreen.Point(cols / 2.0, rows / 2.0) + translate
                val EMPTY_RENDER = { text(" ") }
                val map = MutableList(rows) { // number of rows
                    MutableList(cols) { // number of columns
                        EMPTY_RENDER
                    }
                }

                // put a star in the center
                map[centerVector.y.toInt()][centerVector.x.toInt()] = { red { text("S") } }
                wp.forEach { w ->
                    val pos = (SystemSubScreen.Point(w.x.toDouble(), w.y.toDouble()) / zoom) + centerVector
                    val y = pos.y.roundToInt()
                    val x = pos.x.roundToInt()

                    // cull out of bounds objects
                    if (x in 0..<cols && y in 0..<rows) {
                        objectsOnScreen++
                        val selected = visibleWaypoints.size == selectIndex
                        if (selected) {
                            selectedWaypoint = w
                            selectedMarket = GameState.markets[w.symbol]
                        }
                        visibleWaypoints.add(w)
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
                applyEffects(map)
            }

            cell(colSpan = 2, rowSpan = 4) {
                rgb(HEADER_COLOR.rgb) { makeHeader("Waypoints In ${getHqSystem().symbol}", 2) }
                visibleWaypoints.take(30).forEachIndexed { i, w ->
                    val entry = {
                        text("${w.symbol} ")
                        rgb(w.type.color.rgb) { text(w.type.name) }
                        text("@${w.x},${w.y}")
                    }
                    if (i == selectIndex) {
                        underline {
                            entry()
                        }
                    } else {
                        entry()
                    }
                    textLine()
                }
            }

            cell(colSpan = 2) {
                rgb(HEADER_COLOR.rgb) { makeHeader("Waypoint View", 2) }
                if (selectedWaypoint != null) {
                    // initialize empty grid
                    val rows = 12
                    val cols = 40
                    val centerVector = SystemSubScreen.Point(cols / 2.0, rows / 2.0)
                    val EMPTY_RENDER = { text(" ") }
                    val map = MutableList(rows) { // number of rows
                        MutableList(cols) { // number of columns
                            EMPTY_RENDER
                        }
                    }

                    // We need a fixed seed so everything doesn't change each time
                    // use the waypoints symbol so each planet is uniquely generated
                    var random = Random(selectedWaypoint!!.symbol.hashCode())

                    // draw stars
                    for (i in 0..<rows) {
                        for (j in 0..<cols) {
                            if (random.nextInt(39) == 0) {
                                map[i][j] = {
                                    scopedState {
                                        if (Random.nextInt(64) == 0) rgb(Color.gray.rgb)
                                        bold { text(".") }
                                    }
                                }
                            }
                        }
                    }

                    // draw planet

                    // 0.75 and 0.25 gives better looking circles than 0.0 or 0.5
                    random = Random(selectedWaypoint!!.symbol.hashCode())
                    val radius = 4.75
                    val top = ceil(centerVector.y - radius)
                    val bot = floor(centerVector.y + radius)
                    var y = top
                    while (y <= bot) {
                        val dy = y - centerVector.y
                        val dx = sqrt(radius * radius - dy * dy)

                        // we apply a "fattening" along the x-axis to compensate for font-height/width ratio
                        val left = ceil(centerVector.x * 1.667 - dx * 1.667) - centerVector.x / 1.5
                        val right = floor(centerVector.x * 1.667 + dx * 1.667) - centerVector.x / 1.5
                        var x = left
                        while (x <= right) {
                            map[y.toInt()][x.toInt()] = {
                                rgb(Color(30, 50, 190).rgb) {
                                    if (random.nextInt(4) < 3) text("█") else text("▓")
                                }
                            }
                            x++
                        }
                        y++
                    }

                    // draw atmosphere


                    map.forEach { row ->
                        row.forEach { c ->
                            c()
                        }
                        textLine()
                    }
                }
            }
            cell {
                rgb(HEADER_COLOR.rgb) { makeHeader("Waypoint ${selectedWaypoint?.symbol}") }
                if (selectedWaypoint != null) {
                    textLine("Orbitals: ${selectedWaypoint!!.orbitals.size}")
                    if (selectedWaypoint!!.orbits != null) textLine("Orbits ${selectedWaypoint!!.orbits}")
                    textLine("Traits: ${selectedWaypoint!!.waypointTraits.map { t -> t.symbol }.joinToString(", ")}")
                    textLine("Modifiers: ${selectedWaypoint!!.modifiers.joinToString { ", " }}")
                }
            }

            // ROW 2
            cell {
                rgb(HEADER_COLOR.rgb) { makeHeader("Imports") }
                if (selectedWaypoint != null && selectedMarket != null) {
                    textLine(selectedMarket!!.imports.map { i -> i.symbol }.joinToString(", "))
                }
            }
            cell {
                rgb(HEADER_COLOR.rgb) { makeHeader("Exports") }
                if (selectedWaypoint != null && selectedMarket != null) {
                    textLine(selectedMarket!!.exports.map { i -> i.symbol }.joinToString(", "))
                }
            }

            cell {
                rgb(HEADER_COLOR.rgb) { makeHeader("Exchange") }
                if (selectedWaypoint != null && selectedMarket != null) {
                    textLine(selectedMarket!!.exchange.map { i -> i.symbol }.joinToString(", "))
                }
            }

            // row 3
            cell(colSpan = 3) {
                rgb(HEADER_COLOR.rgb) { makeHeader("Prices", 3) }
                if (selectedWaypoint != null && selectedMarket != null) {
                    selectedMarket!!.tradeGoods.forEach { t ->
                        textLine("${t.symbol} ASK:${t.sellPrice} BUY:${t.purchasePrice} VOL: ${t.tradeVolume} SUP: ${t.supply} ACT: ${t.activity}")
                    }
                }
            }

            // row 4
            cell(colSpan = 3) {
                rgb(HEADER_COLOR.rgb) { makeHeader("Unused", 3) }
            }

            // below system view, row 5
            cell(colSpan = 2) {
                val headerColor = if (selectedQuad != QuadSelect.MAIN) HEADER_COLOR else SELECTED_HEADER_COLOR
                when (currentView) {
                    Window.MAIN -> {
                        rgb(headerColor.rgb) {
                            makeHeader("Fleet Status: ${getHqSystem().symbol}", 2)
                        }

                        getShips().forEach { s ->
                            text("${shortName(s)} [")
                            applyShipRoleColor(s.registration.role)
                            val status = s.script?.currentState ?: "No Script"
                            text("] $status")
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
                    makeHeader("Agent")
                }
                textLine("${GameState.agent.symbol}: $${GameState.agent.credits}")
                textLine("${GameState.ships.size} 🚀 ${GameState.scriptsRunning.size} 📰")
            }

            cell {
                val blocks = arrayOf(" ", "▁", "▂", "▃", "▄", "▅", "▆", "▇", "█")
                val FULL_BLOCK = blocks[8]
                rgb(HEADER_COLOR.rgb) {
                    makeHeader("Job Pressure")
                }
                val pressureWindow = SpaceTradersClient.jobPressureWindow
                (0..4).forEach { h ->
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

            }

            cell {

            }

            cell {

            }

            cell {

            }

            // ROW 3

            cell(colSpan = 2) {

                val headerColor = if (selectedQuad != QuadSelect.COMM) HEADER_COLOR else SELECTED_HEADER_COLOR
                rgb(headerColor.rgb) {
                    makeHeader("Command", 2)
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

            cell(colSpan = 2) {

                val headerColor = if (selectedQuad != QuadSelect.NOTF) HEADER_COLOR else SELECTED_HEADER_COLOR
                rgb(headerColor.rgb) {
                    makeHeader("Notifications", 2)
                }
                NotificationManager.notifications.forEach { n ->
                    rgb(n.animColor.rgb) {
                        text(n.textAnim)
                    }
                    textLine(n.toast)
                }
            }

            cell(colSpan = 2) {
                rgb(HEADER_COLOR.rgb) { makeHeader("Console Stats", 2) }
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

    private fun OffscreenRenderScope.applyEffects(
        map: MutableList<MutableList<() -> Unit>>,
    ) {
        map.forEach { row ->
            row.forEach { c ->
                c()
            }
            textLine()
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
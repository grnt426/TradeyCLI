package bridge.views

import bridge.BridgeModel
import bridge.Format
import bridge.RecentJumps
import bridge.canvas.Attr
import bridge.canvas.DotCanvas
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Rgb
import bridge.fx.Starfield
import bridge.glyphs.Atlas
import bridge.glyphs.Palette
import bridge.scene.Keys
import bridge.scene.Line
import bridge.scene.Table
import bridge.scene.TextBlock
import bridge.scene.Widget
import bridge.tty.Input
import model.system.OrbitalNames
import model.system.System
import model.system.WaypointType
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/**
 * The galaxy: every system we know as a star on a map you can pan and zoom, our home and the
 * leaderboard's homes named, the leaderboards with our place on them, and the server's numbers
 * and announcements. `L` loads the whole galaxy, `R` ranks us against every agent; both say
 * what they will cost in requests first.
 */
class GalaxyView : WidgetView() {
    override val title = "Galaxy"
    private var model: BridgeModel? = null
    private val stars = Starfield(seed = 23, density = 0.004)

    private val map = GalaxyMap { model!! }
    private val leaders = Table(
        columns = listOf(
            Table.Column("#", 3, alignRight = true),
            Table.Column("agent", 14),
            Table.Column("credits", 10, alignRight = true),
            Table.Column("cr/h", 7, alignRight = true),
            Table.Column("cr/ship", 7, alignRight = true),
            Table.Column("ships", 5, alignRight = true),
            Table.Column("home"),
        ),
        onSelect = { row -> row?.let { r -> model?.galaxy?.agents?.get(r.key)?.let { a -> map.selected = OrbitalNames.getSectorSystem(a.headquarters) } } },
        onActivate = { row -> model?.galaxy?.agents?.get(row.key)?.let { a -> map.centreOn(OrbitalNames.getSectorSystem(a.headquarters)) } },
    )
    private val charts = Table(
        columns = listOf(Table.Column("#", 3, alignRight = true), Table.Column("agent", 14), Table.Column("charts", 7, alignRight = true)),
    )
    private val neighbours = Table(
        columns = listOf(
            Table.Column("agent", 14),
            Table.Column("faction", 8),
            Table.Column("credits", 9, alignRight = true),
            Table.Column("ships", 5, alignRight = true),
            Table.Column("seen trading"),
        ),
    )
    private val facts = TextBlock(lines = { factLines() }, empty = "waiting for the server status")

    override fun paint(p: Painter, model: BridgeModel, t: Double) {
        this.model = model
        beginFrame()
        stars.paint(p, t)
        val snap = model.snapshot()
        val galaxy = model.galaxy
        val status = galaxy.status()
        val now = model.now()

        val sideW = (p.width * 0.36).toInt().coerceIn(46, 70)
        val (mapRect, side) = Rect(0, 0, p.width, p.height).cols(Len.weight(), Len.fixed(sideW))
        val tall = p.height >= 44
        val (leadersRect, neighboursRect, chartsRect, factsRect) = side.rows(Len.fixed(19), Len.fixed(if (p.height >= 36) 9 else 0), Len.fixed(if (tall) 8 else 0), Len.weight())

        val gates = "${galaxy.gatesMapped} gates read" +
            (if (galaxy.gatesUnbuilt > 0) ", ${galaxy.gatesUnbuilt} unbuilt (◌, dotted links blocked)" else "") +
            (if (galaxy.gatesUnreadable > 0) ", ${galaxy.gatesUnreadable} uncharted" else "")
        place(map, p.panel(mapRect, "Known galaxy · ${galaxy.systems().size} systems" + (status?.stats?.systems?.let { " of $it" } ?: "") + " · $gates", focus === map, hint = "Enter opens"), t)

        val ours = snap.agent?.symbol
        val board = status?.leaderboards?.mostCredits ?: emptyList()
        // Credits per hour and per ship per hour over the last day's samples; blank until the run has sampled an agent for half an hour.
        val rates = model.leaderRates()
        fun perHour(symbol: String) = rates[symbol]?.let { Format.compact(it.perHour.toLong()) } ?: ""
        fun perShip(symbol: String) = rates[symbol]?.let { Format.compact(it.perShipHour.toLong()) } ?: ""
        val rows = board.mapIndexed { i, e ->
            val a = galaxy.agents[e.agentSymbol]
            Table.Row(
                e.agentSymbol,
                listOf((i + 1).toString(), e.agentSymbol, Format.compact(e.credits), perHour(e.agentSymbol), perShip(e.agentSymbol), a?.shipCount?.toString() ?: "", a?.let { OrbitalNames.getSectorSystem(it.headquarters) } ?: ""),
                if (e.agentSymbol == ours) Palette.accent else Palette.text,
            )
        }.toMutableList()
        val onBoard = board.any { it.agentSymbol == ours }
        if (!onBoard && snap.agent != null) {
            val rank = galaxy.rank
            val label = rank?.let { "#${it.position} of ${it.of}" } ?: "below the board · ranking in idle moments"
            val gap = board.lastOrNull()?.let { " · ${Format.compact(it.credits - snap.agent!!.credits)} short of #${board.size}" } ?: ""
            rows += Table.Row(ours!!, listOf(rank?.position?.toString() ?: "?", ours, Format.compact(snap.agent!!.credits), perHour(ours), perShip(ours), snap.ships.size.toString(), "$label$gap"), Palette.accent)
        }
        leaders.setRows(rows)
        val boardTitle = "Most credits" + (status?.stats?.agents?.let { " · $it agents" } ?: "")
        place(leaders, p.panel(leadersRect, boardTitle, focus === leaders, hint = "cr/h and cr/ship over the last day · Enter centres the map on their home"), t)

        charts.setRows((status?.leaderboards?.mostSubmittedCharts ?: emptyList()).mapIndexed { i, e ->
            Table.Row(e.agentSymbol, listOf((i + 1).toString(), e.agentSymbol, e.chartCount.toString()), if (e.agentSymbol == ours) Palette.accent else Palette.text)
        })
        if (neighboursRect.h > 0) {
            neighbours.setRows(neighbourRows(model))
            val home = snap.hqSystem ?: "?"
            place(neighbours, p.panel(neighboursRect, "Neighbours · headquartered in $home", hint = if (galaxy.allAgents.isEmpty()) "after the ranking pass" else ""), t)
        }
        if (chartsRect.h > 0) place(charts, p.panel(chartsRect, "Most charts"), t)
        place(facts, p.panel(factsRect, "Galaxy" + (status?.version?.let { " · $it" } ?: "")), t)
        endFrame(listOf(map, leaders))
    }

    /**
     * Agents whose headquarters is our home system, from the last ranking pass, with how often
     * their ships show up in the transaction history of the markets we have read.
     */
    private fun neighbourRows(model: BridgeModel): List<Table.Row> {
        val snap = model.snapshot()
        val home = snap.hqSystem ?: return emptyList()
        val ours = snap.agent?.symbol
        val seen = HashMap<String, Pair<Int, String>>()
        snap.marketsIn(home).forEach { m ->
            m.transactions.forEach { tx ->
                val agent = tx.shipSymbol.substringBeforeLast('-')
                val (n, last) = seen[agent] ?: (0 to "")
                seen[agent] = (n + 1) to maxOf(last, tx.timestamp)
            }
        }
        val here = model.galaxy.allAgents.filter { OrbitalNames.getSectorSystem(it.headquarters) == home }.associateBy { it.symbol }.toMutableMap()
        val rows = ArrayList<Table.Row>()
        for (a in here.values.sortedByDescending { it.credits }) {
            val s = seen[a.symbol]
            rows += Table.Row(a.symbol, listOf(a.symbol, a.startingFaction, Format.compact(a.credits), a.shipCount.toString(), s?.let { "${it.first} trades, last ${it.second.take(16).replace('T', ' ')}" } ?: ""), if (a.symbol == ours) Palette.accent else Palette.text)
        }
        // Ships trading here whose owners are headquartered elsewhere.
        seen.filterKeys { it != ours && it !in here }.entries.sortedByDescending { it.value.first }.forEach { (agent, v) ->
            rows += Table.Row(agent, listOf(agent, "", "", "", "visitor · ${v.first} trades, last ${v.second.take(16).replace('T', ' ')}"), Palette.textDim)
        }
        return rows
    }

    private fun factLines(): List<Line> {
        val m = model ?: return emptyList()
        val status = m.galaxy.status() ?: return emptyList()
        val now = m.now()
        val lines = mutableListOf<Line>()
        status.stats?.let { s ->
            lines += Line("${s.agents} agents flying ${s.ships} ships across ${s.systems} systems and ${s.waypoints} waypoints" + (s.accounts?.let { ", $it accounts" } ?: ""), Palette.text)
        }
        status.serverResets?.next?.takeIf { it.isNotBlank() }?.let { next ->
            runCatching { Instant.parse(next) }.getOrNull()?.let { lines += Line("next reset in ${Format.span(Duration.between(now, it))}, ${status.serverResets.frequency.lowercase()}", Palette.textDim) }
        }
        status.health?.lastMarketUpdate?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { lines += Line("markets last updated ${Format.age(it, now)} ago", Palette.textDim) }
        m.galaxy.progress?.let { lines += Line(it, Palette.warn) }
        if (status.announcements.isNotEmpty()) {
            lines += Line("", Palette.text)
            status.announcements.forEach { a ->
                lines += Line(a.title, Palette.textBright, bold = true)
                lines += Line(a.body, Palette.textDim)
            }
        }
        return lines
    }

    override fun onInput(input: Input, model: BridgeModel): Boolean {
        if (input is Input.Key) {
            when (Keys.normalise(input.key)) {
                "L" -> { model.galaxy.loadGalaxy(); return true }
                "R" -> { model.galaxy.rankUs(force = true); return true }
            }
        }
        return super.onInput(input, model)
    }
}

/**
 * The map of systems: a camera over galactic coordinates, stars by type, homes named, gate links
 * drawn by whether a jump can cross them, and the jumps our ships made in the last five minutes
 * animated along the links they took.
 */
class GalaxyMap(private val model: () -> BridgeModel) : Widget() {
    override val focusable = true
    var selected: String? = null
    private var cx = 0.0
    private var cy = 0.0
    private var unitsPerRow = 0.0
    private var lastClickAt = 0L
    private var lastClickKey: String? = null
    private class Placed(val col: Int, val row: Int, val symbol: String)
    private var placed: List<Placed> = emptyList()

    /**
     * What is known of a system's gate. A charted gate answers the jump-gate endpoint while it
     * is still being built, so a system can sit in the middle of the drawn network and still be
     * an island: nothing can jump to it until its gate is finished.
     */
    enum class Gate { OPEN, UNBUILT, UNKNOWN, NONE }

    fun fit() { unitsPerRow = 0.0 }

    fun centreOn(symbol: String) {
        val s = model().galaxy.systems()[symbol] ?: run { model().galaxy.requestSystem(symbol); return }
        cx = s.x.toDouble()
        cy = s.y.toDouble()
        selected = symbol
    }

    private fun col(wx: Double, w: Int) = w / 2.0 + (wx - cx) / unitsPerRow * 2
    private fun row(wy: Double, h: Int) = h / 2.0 + (wy - cy) / unitsPerRow

    override fun paint(p: Painter, focused: Boolean, t: Double) {
        val m = model()
        val snap = m.snapshot()
        val now = m.now()
        val systems = m.galaxy.systems().values
        val w = p.width
        val h = p.height
        if (systems.isEmpty()) {
            p.text(1, 1, "no systems known yet", Palette.textDim)
            return
        }
        if (unitsPerRow <= 0.0) {
            val xs = systems.map { it.x }
            val ys = systems.map { it.y }
            cx = (xs.min() + xs.max()) / 2.0
            cy = (ys.min() + ys.max()) / 2.0
            unitsPerRow = maxOf((ys.max() - ys.min()).coerceAtLeast(10).toDouble() / (h - 4).coerceAtLeast(1), (xs.max() - xs.min()).coerceAtLeast(10).toDouble() / ((w - 8).coerceAtLeast(1) / 2.0)) * 1.05
        }
        val home = snap.hqSystem
        val homes = HashMap<String, MutableList<String>>()
        val board = m.galaxy.status()?.leaderboards?.mostCredits?.map { it.agentSymbol } ?: emptyList()
        for ((symbol, agent) in m.galaxy.agents) homes.getOrPut(OrbitalNames.getSectorSystem(agent.headquarters)) { ArrayList() }.add(symbol)
        val lookup = m.galaxy.systems()

        // Gate state per system, from every source: a gate one of our ships has seen, the plan's
        // record of a system entered, and the gate waypoints the console reads in idle moments.
        val seenGates = snap.waypoints.values.filter { it.type == WaypointType.JUMP_GATE }.associateBy { it.systemSymbol }
        val plan = snap.plan
        val gateCache = HashMap<String, Gate>()
        fun gateOf(sys: String): Gate = gateCache.getOrPut(sys) {
            gateStanding(
                seen = seenGates[sys]?.isUnderConstruction,
                planBuilt = plan?.systems?.get(sys)?.gateBuilt == true,
                read = m.galaxy.gateStates[sys]?.underConstruction,
            ) { m.galaxy.gateSymbols.containsKey(sys) || lookup[sys]?.waypoints?.any { it.type == WaypointType.JUMP_GATE } ?: true }
        }
        val homeGate = home?.let { gateOf(it) }
        val homeOpen = homeGate == Gate.OPEN

        // Gate connections first, under the stars. A link either of whose gates is unfinished is
        // dotted amber: charted, but no jump can cross it. The home's are in the accent once our
        // gate is open, the selection's bright, the rest dim.
        val dots = DotCanvas(w, h, m.dots)
        for ((from, tos) in m.galaxy.connections) {
            val a = lookup[from] ?: continue
            for (to in tos) {
                if (to < from && m.galaxy.connections.containsKey(to)) continue // drawn from the other end
                val b = lookup[to] ?: continue
                val ax = col(a.x.toDouble(), w)
                val ay = row(a.y.toDouble(), h)
                val bx = col(b.x.toDouble(), w)
                val by = row(b.y.toDouble(), h)
                val inView = (ax in 0.0..w.toDouble() && ay in 0.0..h.toDouble()) || (bx in 0.0..w.toDouble() && by in 0.0..h.toDouble())
                if (!inView) continue
                val touchesHome = from == home || to == home
                val touchesSelected = from == selected || to == selected
                val blocked = gateOf(from) == Gate.UNBUILT || gateOf(to) == Gate.UNBUILT
                val colour = when {
                    touchesSelected && blocked -> Palette.warn
                    touchesSelected -> Palette.textBright
                    blocked -> Palette.warn.mix(Palette.background, 0.45)
                    touchesHome && homeOpen -> Palette.accent.mix(Palette.background, 0.3)
                    touchesHome -> Palette.warn.mix(Palette.background, 0.4)
                    else -> Palette.border.mix(Palette.background, 0.35)
                }
                dots.line((ax * m.dots.dotsX).roundToInt(), (ay * m.dots.dotsY).roundToInt(), (bx * m.dots.dotsX).roundToInt(), (by * m.dots.dotsY).roundToInt(), colour, stride = if (blocked) 3 else 1)
            }
        }

        // Recent jumps: the link lit in the accent, fading as the jump ages, and a spark per jump
        // (four at most) running from the gate left to the gate reached, so the direction shows.
        val hops = RecentJumps.recent(snap.activities, now)
        class JumpLabel(val col: Int, val row: Int, val text: String)
        val jumpLabels = ArrayList<JumpLabel>()
        for ((link, list) in RecentJumps.byLink(hops)) {
            val a = lookup[link.first]
            val b = lookup[link.second]
            if (a == null || b == null) {
                if (a == null) m.galaxy.requestSystem(link.first)
                if (b == null) m.galaxy.requestSystem(link.second)
                continue
            }
            val ax = col(a.x.toDouble(), w)
            val ay = row(a.y.toDouble(), h)
            val bx = col(b.x.toDouble(), w)
            val by = row(b.y.toDouble(), h)
            val inView = (ax in 0.0..w.toDouble() && ay in 0.0..h.toDouble()) || (bx in 0.0..w.toDouble() && by in 0.0..h.toDouble())
            if (!inView) continue
            val freshest = list.first().age(now)
            dots.line((ax * m.dots.dotsX).roundToInt(), (ay * m.dots.dotsY).roundToInt(), (bx * m.dots.dotsX).roundToInt(), (by * m.dots.dotsY).roundToInt(), Palette.accent.mix(Palette.background, 0.1 + 0.5 * freshest))
            val sparks = minOf(list.size, 4)
            for (i in 0 until sparks) {
                val head = ((t / SPARK_SECONDS) + i.toDouble() / sparks) % 1.0
                val colour = Palette.textBright.mix(Palette.accent, list[i].age(now))
                for (k in 0 until 3) {
                    val f = head - k * 0.02
                    if (f < 0.0) continue
                    dots.set(((ax + (bx - ax) * f) * m.dots.dotsX).roundToInt(), ((ay + (by - ay) * f) * m.dots.dotsY).roundToInt(), colour)
                }
            }
            // The count only once zoomed in far enough for system names: zoomed out, the sparks say enough and the counts clutter.
            if (unitsPerRow < JUMP_LABEL_ZOOM) {
                val mc = ((ax + bx) / 2).roundToInt()
                val mr = ((ay + by) / 2).roundToInt()
                jumpLabels += JumpLabel(mc + 1, mr, if (list.size == 1) "1 jump" else "${list.size} jumps")
            }
        }
        dots.paint(p, 0, 0)

        val out = ArrayList<Placed>()
        val dense = unitsPerRow > 400
        // Notable systems last: with the whole galaxy on screen several share a cell, and the one drawn last shows.
        fun notable(s: System) = s.symbol == home || s.symbol == selected || homes.containsKey(s.symbol) ||
            m.galaxy.gateStates[s.symbol]?.underConstruction == true || seenGates[s.symbol]?.isUnderConstruction == true
        for (s in systems.sortedBy { notable(it) }) {
            val c = col(s.x.toDouble(), w).roundToInt()
            val r = row(s.y.toDouble(), h).roundToInt()
            if (c !in 0 until w || r !in 0 until h) continue
            val colour = Atlas.star(s.type)
            val isHome = s.symbol == home
            val isSelected = s.symbol == selected
            val named = homes[s.symbol]
            val gate = gateOf(s.symbol)
            val unbuilt = gate == Gate.UNBUILT
            val glyph = when {
                isHome -> '◉'
                unbuilt -> '◌'
                named != null -> '◆'
                dense -> '·'
                else -> '•'
            }
            val fg = when {
                isSelected -> Palette.textBright
                isHome -> Palette.accent
                unbuilt && dense -> Palette.background.mix(Palette.warn, 0.6)
                unbuilt -> Palette.warn
                named != null -> colour
                dense -> Palette.background.mix(colour, 0.55)
                else -> colour
            }
            p.put(c, r, glyph, fg, if (isSelected) Palette.selection else null, if (isHome || named != null || unbuilt) Attr.BOLD else Attr.NONE)
            out += Placed(c, r, s.symbol)
            // Other agents' homes keep their diamond at any zoom but are named only once zoomed in: the core is thick with them.
            val label = when {
                isHome -> "${snap.agent?.symbol ?: "home"} · ${s.symbol}"
                named != null && unitsPerRow < HOME_LABEL_ZOOM -> named.joinToString(", ") { a -> board.indexOf(a).let { i -> if (i >= 0) "#${i + 1} $a" else a } }
                isSelected || unitsPerRow < 60 -> s.symbol
                else -> null
            }
            if (label != null) p.text(c + 2, r, label, if (isHome) Palette.accent else if (named != null) Palette.text else Palette.textDim)
        }
        placed = out
        // Jump counts at the midpoints, nudged down a row when a star sits there or another count does.
        val taken = HashSet<Long>()
        fun key(c: Int, r: Int) = r.toLong() shl 32 or (c.toLong() and 0xffffffffL)
        for (l in jumpLabels) {
            var r = l.row
            var tries = 0
            while (tries++ < 3 && (out.any { it.row == r && it.col in (l.col - 1)..(l.col + l.text.length) } || (l.col..(l.col + l.text.length)).any { key(it, r) in taken })) r++
            if (r !in 0 until h) continue
            p.text(l.col, r, l.text, Palette.accent, null, Attr.BOLD)
            for (c in l.col..(l.col + l.text.length)) taken += key(c, r)
        }

        val sel = selected?.let { lookup[it] }
        if (sel != null) {
            val links = m.galaxy.connections[sel.symbol]
            val gate = gateOf(sel.symbol)
            val state = m.galaxy.gateStates[sel.symbol]
            val gateText = when (gate) {
                Gate.OPEN -> "gate open"
                Gate.UNBUILT -> "gate under construction, cannot be jumped to" + (state?.let { ", read ${Format.age(it.readAt, now)} ago" } ?: "")
                Gate.NONE -> "no gate"
                Gate.UNKNOWN -> if (links != null || m.galaxy.gateSymbols.containsKey(sel.symbol)) "gate state not read" else "gate not read yet"
            }
            val card = "${sel.symbol} · ${sel.type.lowercase().replace('_', ' ')} · sector ${sel.sectorSymbol} · ${sel.x}, ${sel.y} · ${sel.waypoints.size} waypoints" +
                (if (sel.factions.isNotEmpty()) " · ${sel.factions.joinToString { it.symbol.toString() }}" else "") +
                " · $gateText" +
                (links?.let { " · links ${it.size}: ${it.sorted().take(6).joinToString(", ")}${if (it.size > 6) ", …" else ""}" } ?: "") +
                (homes[sel.symbol]?.let { " · home of ${it.joinToString()}" } ?: "") +
                (if (snap.waypointsIn(sel.symbol).isNotEmpty()) " · Enter opens" else " · Enter loads and opens")
            p.text(1, 0, card.take(w - 2), if (gate == Gate.UNBUILT) Palette.warn else Palette.text)
        }
        val gateNote = when (homeGate) {
            null, Gate.NONE -> "no gate at home"
            Gate.OPEN -> "home gate open, accent links ours"
            Gate.UNBUILT -> "home gate unfinished: links charted, not ours yet"
            Gate.UNKNOWN -> "home gate not read"
        }
        val jumps = if (hops.isEmpty()) "" else " · ${hops.size} jump${if (hops.size == 1) "" else "s"} in ${RecentJumps.WINDOW.toMinutes()} min"
        val hint = (if (focused) "arrows · +/- · f fit · Enter opens · R re-rank" else "click to focus") + "$jumps · $gateNote"
        p.text(1, h - 1, hint.take(w - 2), Palette.textDim)
    }

    override fun onKey(key: Input.Key): Boolean {
        val stepX = unitsPerRow * rect.w / 2 * 0.15
        val stepY = unitsPerRow * rect.h * 0.15
        when (Keys.normalise(key.key)) {
            "ArrowLeft" -> cx -= stepX
            "ArrowRight" -> cx += stepX
            "ArrowUp" -> cy -= stepY
            "ArrowDown" -> cy += stepY
            "+", "=" -> unitsPerRow /= 1.25
            "-", "_" -> unitsPerRow *= 1.25
            "f" -> fit()
            "c" -> selected?.let { centreOn(it) } ?: return false
            "Enter" -> { open(); return true }
            else -> return false
        }
        return true
    }

    /** Opens the selected system on the system screen; one no ship of ours has visited has its waypoints fetched first. */
    private fun open() {
        val sel = selected ?: return
        model().openSystem(sel)
    }

    override fun onMouse(mouse: Input.Mouse, x: Int, y: Int): Boolean {
        when {
            mouse.wheelUp || mouse.wheelDown -> {
                val wx = cx + (x - rect.w / 2.0) / 2 * unitsPerRow
                val wy = cy + (y - rect.h / 2.0) * unitsPerRow
                unitsPerRow = if (mouse.wheelUp) unitsPerRow / 1.25 else unitsPerRow * 1.25
                cx = wx - (x - rect.w / 2.0) / 2 * unitsPerRow
                cy = wy - (y - rect.h / 2.0) * unitsPerRow
                return true
            }
            mouse.left -> {
                val hit = placed.minByOrNull { kotlin.math.abs(it.col - x) / 2.0 + kotlin.math.abs(it.row - y) }?.takeIf { kotlin.math.abs(it.col - x) / 2.0 + kotlin.math.abs(it.row - y) <= 1.5 } ?: return true
                selected = hit.symbol
                val now = java.lang.System.nanoTime()
                if (hit.symbol == lastClickKey && now - lastClickAt < Table.DOUBLE_CLICK_NANOS) {
                    centreOn(hit.symbol)
                    open()
                    lastClickAt = 0
                } else {
                    lastClickAt = now
                    lastClickKey = hit.symbol
                }
                return true
            }
        }
        return false
    }

    companion object {
        /** How long a spark takes to run the length of a link. */
        const val SPARK_SECONDS = 2.5

        /** Units per row below which jump counts are written on the links: the zoom at which every system is named. */
        const val JUMP_LABEL_ZOOM = 60.0

        /** Units per row below which other agents' homes are named; further out only their diamonds show. */
        const val HOME_LABEL_ZOOM = 100.0

        /**
         * A system's gate from what each source says. A gate seen finished by a ship, or a system
         * the plan entered, is open for good; otherwise the console's own read decides; a gate a
         * ship saw under construction, unread since, counts as unbuilt; a system with no gate
         * waypoint has none. [hasGate] is asked only when nothing else is known, since with the
         * whole galaxy on screen it is asked for thousands of systems a frame.
         */
        fun gateStanding(seen: Boolean?, planBuilt: Boolean, read: Boolean?, hasGate: () -> Boolean): Gate = when {
            seen == false || planBuilt -> Gate.OPEN
            read != null -> if (read) Gate.UNBUILT else Gate.OPEN
            seen == true -> Gate.UNBUILT
            !hasGate() -> Gate.NONE
            else -> Gate.UNKNOWN
        }
    }
}

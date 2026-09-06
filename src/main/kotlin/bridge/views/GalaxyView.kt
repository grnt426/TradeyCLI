package bridge.views

import bridge.BridgeModel
import bridge.Format
import bridge.canvas.Attr
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

        place(map, p.panel(mapRect, "Known galaxy · ${galaxy.systems().size} systems" + (status?.stats?.systems?.let { " of $it" } ?: ""), focus === map), t)

        val ours = snap.agent?.symbol
        val board = status?.leaderboards?.mostCredits ?: emptyList()
        val rows = board.mapIndexed { i, e ->
            val a = galaxy.agents[e.agentSymbol]
            Table.Row(
                e.agentSymbol,
                listOf((i + 1).toString(), e.agentSymbol, Format.compact(e.credits), a?.shipCount?.toString() ?: "", a?.let { OrbitalNames.getSectorSystem(it.headquarters) } ?: ""),
                if (e.agentSymbol == ours) Palette.accent else Palette.text,
            )
        }.toMutableList()
        val onBoard = board.any { it.agentSymbol == ours }
        if (!onBoard && snap.agent != null) {
            val rank = galaxy.rank
            val label = rank?.let { "#${it.position} of ${it.of}" } ?: "below the board · ranking in idle moments"
            val gap = board.lastOrNull()?.let { " · ${Format.compact(it.credits - snap.agent!!.credits)} short of #${board.size}" } ?: ""
            rows += Table.Row(ours!!, listOf(rank?.position?.toString() ?: "?", ours, Format.compact(snap.agent!!.credits), snap.ships.size.toString(), "$label$gap"), Palette.accent)
        }
        leaders.setRows(rows)
        val boardTitle = "Most credits" + (status?.stats?.agents?.let { " · $it agents" } ?: "")
        place(leaders, p.panel(leadersRect, boardTitle, focus === leaders, hint = "Enter centres the map on their home"), t)

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

/** The map of systems: a camera over galactic coordinates, stars by type, homes named. */
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
        val out = ArrayList<Placed>()
        val dense = unitsPerRow > 400
        for (s in systems) {
            val c = col(s.x.toDouble(), w).roundToInt()
            val r = row(s.y.toDouble(), h).roundToInt()
            if (c !in 0 until w || r !in 0 until h) continue
            val colour = Atlas.star(s.type)
            val isHome = s.symbol == home
            val isSelected = s.symbol == selected
            val named = homes[s.symbol]
            val glyph = when {
                isHome -> '◉'
                named != null -> '◆'
                dense -> '·'
                else -> '•'
            }
            val fg = when {
                isSelected -> Palette.textBright
                isHome -> Palette.accent
                named != null -> colour
                dense -> Palette.background.mix(colour, 0.55)
                else -> colour
            }
            p.put(c, r, glyph, fg, if (isSelected) Palette.selection else null, if (isHome || named != null) Attr.BOLD else Attr.NONE)
            out += Placed(c, r, s.symbol)
            val label = when {
                isHome -> "${snap.agent?.symbol ?: "home"} · ${s.symbol}"
                named != null -> named.joinToString(", ") { a -> board.indexOf(a).let { i -> if (i >= 0) "#${i + 1} $a" else a } }
                isSelected || unitsPerRow < 60 -> s.symbol
                else -> null
            }
            if (label != null) p.text(c + 2, r, label, if (isHome) Palette.accent else if (named != null) Palette.text else Palette.textDim)
        }
        placed = out
        val sel = selected?.let { m.galaxy.systems()[it] }
        if (sel != null) {
            val card = "${sel.symbol} · ${sel.type.lowercase().replace('_', ' ')} · sector ${sel.sectorSymbol} · ${sel.x}, ${sel.y} · ${sel.waypoints.size} waypoints" +
                (if (sel.factions.isNotEmpty()) " · ${sel.factions.joinToString { it.symbol.toString() }}" else "") +
                (homes[sel.symbol]?.let { " · home of ${it.joinToString()}" } ?: "") +
                (if (snap.waypointsIn(sel.symbol).isNotEmpty()) " · Enter opens" else "")
            p.text(1, 0, card.take(w - 2), Palette.text)
        }
        val idle = "idle lane: ${m.galaxy.galaxyRequests()} req for the galaxy, ${m.galaxy.rankRequests()} to rank"
        val hint = if (focused) "arrows pan · +/- zoom · f fit · R re-rank now · $idle" else "click to focus · $idle"
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

    /** Opens the selected system on the system screen when its waypoints are loaded. */
    private fun open() {
        val m = model()
        val sel = selected ?: return
        if (m.snapshot().waypointsIn(sel).isEmpty()) return
        m.selectedSystem = sel
        m.navigateTo("System")
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
}

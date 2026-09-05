package bridge.views

import bridge.BridgeModel
import bridge.canvas.Attr
import bridge.canvas.DotCanvas
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.fx.Starfield
import bridge.glyphs.Atlas
import bridge.glyphs.Palette
import bridge.scene.Keys
import bridge.scene.Table
import bridge.scene.Widget
import bridge.tty.Input
import engine.Snapshot
import model.ship.Ship
import model.system.Waypoint
import java.time.Duration
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A star system seen from above. World units map to cells through a camera; a cell is about
 * twice as tall as it is wide, so a world unit spans two columns for every row. Orbitals share
 * their parent's coordinates in the game, so they are set on a ring around it. Ships in flight
 * move along their route with a fading trail behind and the road ahead dotted; ships parked at a
 * waypoint are a count beside it.
 *
 * Keys: arrows pan, `+`/`-` zoom, `f` fits the system, `c` centres on the selection, `[` and `]`
 * change system. Mouse: click selects the nearest object, the wheel zooms about the pointer.
 */
class SystemMap(
    private val model: () -> BridgeModel,
    private val onSelectWaypoint: (String?) -> Unit,
    private val onSelectShip: (String?) -> Unit,
    private val onSelectStar: () -> Unit = {},
) : Widget() {
    override val focusable = true

    var system: String? = null
    var selectedWaypoint: String? = null
    var selectedShip: String? = null
    var selectedStar: Boolean = false

    private var lastClickAt = 0L
    private var lastClickKey: String? = null

    private var cx = 0.0
    private var cy = 0.0

    /** World units per row; 0 means fit the system on the next paint. */
    private var unitsPerRow = 0.0
    private val stars = Starfield(seed = 3, density = 0.004)

    private class Placed(val col: Int, val row: Int, val waypoint: String?, val ship: String?, val star: Boolean = false) {
        val key: String get() = waypoint ?: ship ?: "star"
    }
    private var placed: List<Placed> = emptyList()

    fun fit() {
        unitsPerRow = 0.0
    }

    fun centreOn(wx: Double, wy: Double) {
        cx = wx
        cy = wy
    }

    private fun col(wx: Double, w: Int): Double = w / 2.0 + (wx - cx) / unitsPerRow * 2
    private fun row(wy: Double, h: Int): Double = h / 2.0 + (wy - cy) / unitsPerRow

    private fun fitTo(waypoints: Collection<Waypoint>, w: Int, h: Int) {
        val xs = waypoints.map { it.x } + 0
        val ys = waypoints.map { it.y } + 0
        cx = (xs.min() + xs.max()) / 2.0
        cy = (ys.min() + ys.max()) / 2.0
        val spanX = (xs.max() - xs.min()).coerceAtLeast(10).toDouble()
        val spanY = (ys.max() - ys.min()).coerceAtLeast(10).toDouble()
        unitsPerRow = maxOf(spanY / (h - 4).coerceAtLeast(1), spanX / ((w - 8).coerceAtLeast(1) / 2.0)) * 1.05
    }

    override fun paint(p: Painter, focused: Boolean, t: Double) {
        val m = model()
        val snap = m.snapshot()
        val now = m.now()
        val sys = system ?: snap.hqSystem ?: return
        system = sys
        val waypoints = snap.waypointsIn(sys)
        val w = p.width
        val h = p.height
        if (w < 10 || h < 5) return
        stars.paint(p, t * 0.5)
        if (waypoints.isEmpty()) {
            p.text(1, 1, "no waypoints loaded for $sys", Palette.textDim)
            return
        }
        if (unitsPerRow <= 0.0) fitTo(waypoints, w, h)

        val out = ArrayList<Placed>()
        val labels = HashSet<Long>()
        fun occupy(c: Int, r: Int) = labels.add(r.toLong() shl 32 or (c.toLong() and 0xffffffffL))
        val dots = DotCanvas(w, h, m.dots)

        // Waypoints. Zoomed in, orbitals sit on a ring around their parent, turning very slowly;
        // zoomed out they fold into the parent and their parked ships count towards it.
        val showOrbitals = unitsPerRow < ORBITAL_ZOOM
        val byParent = waypoints.filter { it.orbits != null }.groupBy { it.orbits!! }
        val parkedShips = snap.ships.values.filter { it.nav.systemSymbol == sys && !it.nav.inTransitAt(now) }.groupBy { it.nav.waypointSymbol }
        val parked: Map<String, List<Ship>> = if (showOrbitals) parkedShips else waypoints.filter { it.orbits == null }.associate { wp ->
            wp.symbol to ((parkedShips[wp.symbol] ?: emptyList()) + (byParent[wp.symbol] ?: emptyList()).flatMap { parkedShips[it.symbol] ?: emptyList() })
        }
        val positions = HashMap<String, Pair<Double, Double>>()
        for (wp in waypoints.filter { it.orbits == null }) {
            val c = col(wp.x.toDouble(), w)
            val r = row(wp.y.toDouble(), h)
            positions[wp.symbol] = c to r
            val kids = byParent[wp.symbol] ?: emptyList()
            if (kids.isNotEmpty() && showOrbitals) {
                val radius = 1.6
                ring(dots, c, r, radius, Palette.track.mix(Palette.border, 0.5))
                kids.forEachIndexed { i, kid ->
                    val a = i * 2 * Math.PI / kids.size + t * 0.05
                    positions[kid.symbol] = (c + cos(a) * radius * 2) to (r + sin(a) * radius)
                }
            }
        }
        dots.paint(p, 0, 0)


        for (wp in waypoints) {
            val (c, r) = positions[wp.symbol] ?: continue
            val ci = c.roundToInt()
            val ri = r.roundToInt()
            if (ci !in 0 until w || ri !in 0 until h) continue
            val glyph = Atlas.waypoint(wp.type)
            val selected = wp.symbol == selectedWaypoint
            val colour = if (selected) Palette.textBright else glyph.colour
            p.put(ci, ri, glyph.ch, colour, if (selected) Palette.selection else null, if (selected || wp.hasMarket) Attr.BOLD else Attr.NONE)
            occupy(ci, ri)
            out += Placed(ci, ri, wp.symbol, null)
            val ships = parked[wp.symbol] ?: emptyList()
            val count = ships.size
            var tagX = ci + 1
            if (count > 0) {
                val tone = Atlas.role(ships.first().registration.role)
                p.put(tagX, ri, if (count < 10) ('0' + count) else '+', tone, null, Attr.BOLD)
                occupy(tagX, ri)
                tagX++
            }
            val notable = wp.hasMarket || wp.hasShipyard || wp.isUnderConstruction || wp.type.name.endsWith("GATE")
            val showLabel = selected || (unitsPerRow < LABEL_ZOOM && notable) || (unitsPerRow < LABEL_ZOOM / 3)
            if (showLabel) {
                val label = wp.symbol.substringAfterLast('-')
                if ((tagX until tagX + label.length).none { labels.contains(ri.toLong() shl 32 or (it.toLong() and 0xffffffffL)) }) {
                    p.text(tagX, ri, label, if (selected) Palette.textBright else Palette.textDim)
                    for (x in tagX until tagX + label.length) occupy(x, ri)
                }
            }
        }

        // The star, with a soft halo, over everything but the ships: nothing hides the centre.
        val starColour = Atlas.star(snap.systems[sys]?.type ?: "")
        val sc = col(0.0, w).roundToInt()
        val sr = row(0.0, h).roundToInt()
        val pulse = 0.5 + 0.5 * sin(t * 0.8)
        for ((dc, dr) in listOf(-1 to 0, 1 to 0, -2 to 0, 2 to 0, 0 to -1, 0 to 1)) {
            if (labels.contains((sr + dr).toLong() shl 32 or ((sc + dc).toLong() and 0xffffffffL))) continue // a waypoint or label sits here
            val far = kotlin.math.abs(dc) == 2
            p.put(sc + dc, sr + dr, '·', Palette.background.mix(starColour, if (far) 0.2 + 0.1 * pulse else 0.4 + 0.2 * pulse))
        }
        p.put(sc, sr, Atlas.STAR, if (selectedStar) Palette.textBright else starColour, if (selectedStar) Palette.selection else null, Attr.BOLD)
        occupy(sc, sr)
        out += Placed(sc, sr, null, null, star = true)
        if (selectedStar || unitsPerRow < LABEL_ZOOM) p.text(sc + 2, sr, snap.systems[sys]?.type?.lowercase()?.replace('_', ' ') ?: "star", Palette.textDim)

        // Ships in flight: trail behind, road ahead, sprite.
        val flying = snap.ships.values.filter { it.nav.systemSymbol == sys && it.nav.inTransitAt(now) }
        val paths = DotCanvas(w, h, m.dots)
        for (ship in flying) {
            val route = ship.nav.route
            val total = Duration.between(route.departureTime, route.arrival).toMillis().coerceAtLeast(1)
            val done = Duration.between(route.departureTime, now).toMillis().coerceIn(0, total)
            val f = done.toDouble() / total
            val ox = col(route.origin.x.toDouble(), w)
            val oy = row(route.origin.y.toDouble(), h)
            val dx = col(route.destination.x.toDouble(), w)
            val dy = row(route.destination.y.toDouble(), h)
            val sx = ox + (dx - ox) * f
            val sy = oy + (dy - oy) * f
            val tone = Atlas.role(ship.registration.role)
            val selected = ship.symbol == selectedShip
            // Road ahead: every third dot.
            dotted(paths, sx, sy, dx, dy, Palette.background.mix(tone, 0.35), 3)
            // Trail: brighter towards the ship.
            val steps = 24
            for (i in 0 until steps) {
                val a = i / steps.toDouble()
                val b = (i + 1) / steps.toDouble()
                val fade = 0.15 + 0.85 * b * b
                paths.line(
                    (ox + (sx - ox) * a).dotX(m), (oy + (sy - oy) * a).dotY(m),
                    (ox + (sx - ox) * b).dotX(m), (oy + (sy - oy) * b).dotY(m),
                    Palette.background.mix(tone, fade),
                )
            }
            val ci = sx.roundToInt()
            val ri = sy.roundToInt()
            out += Placed(ci, ri, null, ship.symbol)
        }
        paths.paint(p, 0, 0)
        for (ship in flying) {
            val pl = out.last { it.ship == ship.symbol }
            val selected = ship.symbol == selectedShip
            val tone = Atlas.role(ship.registration.role)
            val flicker = if ((t * 12).toInt() % 3 == 0) Palette.textBright else tone
            p.put(pl.col, pl.row, if (selected) '◈' else '◆', if (selected) Palette.textBright else flicker, if (selected) Palette.selection else null, Attr.BOLD)
            if (selected || unitsPerRow < 6) p.text(pl.col + 1, pl.row, ship.symbol.substringAfterLast('-'), if (selected) Palette.textBright else tone)
        }
        placed = out

        // Corner readout.
        val info = "$sys · ${waypoints.size} waypoints · ${flying.size} in flight · ${"%.0f".format(unitsPerRow)} u/row"
        p.text(1, h - 1, info, Palette.textDim)
        val hint = if (focused) "arrows pan · +/- zoom · f fit · c centre · [ ] system" else "click to focus"
        p.textRight(w - 1, h - 1, hint, Palette.textDim)
    }

    private companion object {
        /** Below this many world units per row, orbitals leave their parent and get a ring. */
        const val ORBITAL_ZOOM = 20.0

        /** Below this, notable waypoints are labelled; at a third of it, every waypoint is. */
        const val LABEL_ZOOM = 12.0
    }

    private fun Double.dotX(m: BridgeModel): Int = (this * m.dots.dotsX).roundToInt()
    private fun Double.dotY(m: BridgeModel): Int = (this * m.dots.dotsY).roundToInt()

    private fun ring(c: DotCanvas, colC: Double, rowR: Double, radius: Double, colour: Rgb) {
        val steps = 48
        for (i in 0 until steps) {
            val a = i * 2 * Math.PI / steps
            val x = ((colC + cos(a) * radius * 2) * c.mode.dotsX).roundToInt()
            val y = ((rowR + sin(a) * radius) * c.mode.dotsY).roundToInt()
            c.set(x, y, colour)
        }
    }

    private fun dotted(c: DotCanvas, x0: Double, y0: Double, x1: Double, y1: Double, colour: Rgb, every: Int) {
        val ax = x0 * c.mode.dotsX
        val ay = y0 * c.mode.dotsY
        val bx = x1 * c.mode.dotsX
        val by = y1 * c.mode.dotsY
        val len = kotlin.math.hypot(bx - ax, by - ay).toInt().coerceAtLeast(1)
        for (i in 0..len step every) {
            val f = i / len.toDouble()
            c.set((ax + (bx - ax) * f).roundToInt(), (ay + (by - ay) * f).roundToInt(), colour)
        }
    }

    private fun systems(snap: Snapshot): List<String> = snap.waypoints.values.map { it.systemSymbol }.distinct().sorted()

    override fun onKey(key: Input.Key): Boolean {
        val snap = model().snapshot()
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
            "c" -> centreOnKey(selectedShip ?: selectedWaypoint ?: "star")
            "[", "]" -> {
                val all = systems(snap)
                if (all.isEmpty()) return false
                val i = all.indexOf(system).coerceAtLeast(0)
                system = all[(i + (if (key.key == "]") 1 else all.size - 1)) % all.size]
                fit()
            }
            else -> return false
        }
        return true
    }

    /** Centres the camera on a waypoint, a ship, or the star; an orbital centres on its parent. */
    fun centreOnKey(key: String) {
        val snap = model().snapshot()
        val wp = snap.waypoints[key]
        val ship = snap.ships[key]
        when {
            wp != null -> { val root = wp.orbits?.let { snap.waypoints[it] } ?: wp; centreOn(root.x.toDouble(), root.y.toDouble()) }
            ship != null -> shipWorld(ship, model().now())?.let { (x, y) -> centreOn(x, y) }
            else -> centreOn(0.0, 0.0)
        }
    }

    private fun shipWorld(ship: Ship, now: java.time.Instant): Pair<Double, Double>? {
        val nav = ship.nav
        if (!nav.inTransitAt(now)) return model().snapshot().waypoints[nav.waypointSymbol]?.let { it.x.toDouble() to it.y.toDouble() }
        val r = nav.route
        val total = Duration.between(r.departureTime, r.arrival).toMillis().coerceAtLeast(1)
        val f = Duration.between(r.departureTime, now).toMillis().coerceIn(0, total).toDouble() / total
        return (r.origin.x + (r.destination.x - r.origin.x) * f) to (r.origin.y + (r.destination.y - r.origin.y) * f)
    }

    override fun onMouse(m: Input.Mouse, x: Int, y: Int): Boolean {
        when {
            m.wheelUp || m.wheelDown -> {
                // Keep the world point under the pointer where it is.
                val wx = cx + (x - rect.w / 2.0) / 2 * unitsPerRow
                val wy = cy + (y - rect.h / 2.0) * unitsPerRow
                unitsPerRow = if (m.wheelUp) unitsPerRow / 1.25 else unitsPerRow * 1.25
                cx = wx - (x - rect.w / 2.0) / 2 * unitsPerRow
                cy = wy - (y - rect.h / 2.0) * unitsPerRow
                return true
            }
            m.left -> {
                val hit = placed
                    .map { it to (kotlin.math.abs(it.col - x) / 2.0 + kotlin.math.abs(it.row - y)) }
                    .filter { it.second <= 1.5 }
                    .sortedWith(compareBy({ if (it.first.ship != null) 0 else 1 }, { it.second }))
                    .firstOrNull()?.first ?: return true
                selectedStar = hit.star
                when {
                    hit.ship != null -> { selectedShip = hit.ship; onSelectShip(hit.ship) }
                    hit.waypoint != null -> { selectedWaypoint = hit.waypoint; selectedShip = null; onSelectWaypoint(hit.waypoint) }
                    else -> { selectedShip = null; onSelectStar() }
                }
                // A second click on the same object soon after centres on it.
                val now = System.nanoTime()
                if (hit.key == lastClickKey && now - lastClickAt < Table.DOUBLE_CLICK_NANOS) {
                    centreOnKey(hit.key)
                    lastClickAt = 0
                } else {
                    lastClickAt = now
                    lastClickKey = hit.key
                }
                return true
            }
        }
        return false
    }
}

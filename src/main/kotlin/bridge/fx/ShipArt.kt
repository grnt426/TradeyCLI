package bridge.fx

import bridge.canvas.Attr
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.glyphs.Atlas
import bridge.glyphs.Palette
import model.ship.Ship

/**
 * Ship silhouettes in side profile, nose to the right, one template per frame family, tinted by
 * role. Template characters: `#` hull, `+` the lit upper hull, `_` the shaded underside, `▄`/`▀`
 * hull edges, `=` engine block, `*` exhaust (streams while in flight), `o` bridge windows,
 * `[`…`]` a cargo section whose interior fills with the hold, `x` a hardpoint on the spine,
 * `^` an antenna, `-` a fin. Everything else is space.
 */
object ShipArt {
    private val PROBE = listOf(
        "               ^          ",
        "      ▄▄▄####++++####▄▄   ",
        " ***===###oo##########▀▀▄ ",
        "      ▀▀▀####____####▀▀   ",
    )
    private val DRONE = listOf(
        "        x       x         ",
        "     ▄▄▄###+++++###▄▄▄    ",
        " ***===###oo###########▀▄ ",
        "     ▀▀▀###[#####]#▀▀▀    ",
        "        -       -         ",
    )
    private val SHUTTLE = listOf(
        "                  ^                 ",
        "        ▄▄▄▄####+++++++####▄▄▄      ",
        "  ***====###[########]###oo####▀▀▄  ",
        "  ***====###[########]############▀ ",
        "        ▀▀▀▀####_______####▀▀▀      ",
    )
    private val FRIGATE = listOf(
        "            x                 x               ",
        "        ▄▄▄▄▄#####++++++++++#####▄▄▄▄         ",
        "  ***=====####[############]####oo#######▀▀▄▄ ",
        "  ***=====####[############]#################▀",
        "  ***=====####[############]####_____######▄▀ ",
        "        ▀▀▀▀▀#####________#####▀▀▀▀         ",
        "             -                -               ",
    )
    private val FREIGHTER = listOf(
        "          x         x         x               ",
        "       ▄▄▄######+++++++++++++######▄▄▄        ",
        "  ***====###[########][########]###oo####▀▀▄  ",
        "  ***====###[########][########]#############▀",
        "  ***====###[########][########]############▄▀",
        "  ***====###[########][########]###______▄▄▀  ",
        "       ▀▀▀######_____________######▀▀▀        ",
    )

    fun template(ship: Ship): List<String> {
        val f = ship.frame.symbol
        return when {
            "PROBE" in f -> PROBE
            "DRONE" in f || "MINER" in f -> DRONE
            "SHUTTLE" in f || "INTERCEPTOR" in f || "RACER" in f || "FIGHTER" in f -> SHUTTLE
            "HEAVY" in f || "BULK" in f || "CARRIER" in f || "CRUISER" in f || "DESTROYER" in f -> FREIGHTER
            "FREIGHTER" in f || "FRIGATE" in f || "EXPLORER" in f -> FRIGATE
            else -> SHUTTLE
        }
    }

    /**
     * Draws [ship] centred in [p]. Cargo sections fill bottom-up with the hold fraction; exhaust
     * streams from the engines while [inFlight]; a frame in poor condition shows scorch marks.
     */
    fun draw(p: Painter, ship: Ship, t: Double, inFlight: Boolean) {
        val rows = template(ship)
        val tw = rows.maxOf { it.length }
        val th = rows.size
        val x0 = (p.width - tw) / 2
        val y0 = (p.height - th) / 2
        val role = Atlas.role(ship.registration.role)
        val hull = role.mix(Rgb(140, 150, 170), 0.55)
        val lit = hull.scale(1.35)
        val shade = hull.scale(0.55)
        val fin = hull.scale(0.7)
        val engine = Rgb(90, 100, 120)
        val window = Rgb(255, 235, 170)
        val fill = ship.cargo.fillRatio
        val holdRows = rows.indices.filter { rows[it].contains('[') }
        val filledRows = holdRows.takeLast((holdRows.size * fill + 0.5).toInt()).toSet()
        val condition = ship.frame.condition ?: 1f
        val scorch = Noise(ship.symbol.hashCode().toLong())
        val hasMounts = ship.mounts.isNotEmpty()
        rows.forEachIndexed { ry, row ->
            row.forEachIndexed { rx, ch ->
                val x = x0 + rx
                val y = y0 + ry
                when (ch) {
                    ' ' -> Unit
                    '#', '▄', '▀' -> {
                        var c = hull
                        if (condition < 0.6 && scorch.at(rx / 2.0, ry.toDouble()) > 0.5 + condition / 2) c = c.scale(0.5)
                        p.put(x, y, if (ch == '#') '█' else ch, c)
                    }
                    '+' -> p.put(x, y, '█', lit)
                    '_' -> p.put(x, y, '█', shade)
                    '-' -> p.put(x, y, '▔', fin)
                    '=' -> p.put(x, y, '▓', engine)
                    '[', ']' -> p.put(x, y, if (ch == '[') '▐' else '▌', hull.scale(0.8))
                    'o' -> p.put(x, y, '▪', window, hull)
                    '^' -> p.put(x, y, '│', Palette.textDim)
                    'x' -> if (hasMounts) p.put(x, y, '┬', Palette.warn) else Unit
                    '*' -> exhaust(p, x, y, rx, row, t, inFlight)
                }
            }
            // Cargo inside each hold section.
            if (ry in holdRows) {
                var open = row.indexOf('[')
                while (open >= 0) {
                    val close = row.indexOf(']', open)
                    if (close < 0) break
                    for (rx in open + 1 until close) {
                        val filled = ry in filledRows
                        p.put(x0 + rx, y0 + ry, if (filled) '▒' else ' ', if (filled) Palette.good else hull, if (filled) hull.scale(0.6) else hull.scale(0.35))
                    }
                    open = row.indexOf('[', close)
                }
            }
        }
    }

    /** Exhaust streams left from the engine: brightest and bluest nearest it, breaking up further out. */
    private fun exhaust(p: Painter, x: Int, y: Int, rx: Int, row: String, t: Double, inFlight: Boolean) {
        if (!inFlight) {
            p.put(x, y, '·', Palette.textDim)
            return
        }
        // Distance from the engine: the last '*' in the run is next to it.
        var end = rx
        while (end + 1 < row.length && row[end + 1] == '*') end++
        val dist = end - rx
        val phase = ((t * 16).toInt() + rx * 2 + y) % 4
        val glyph = when {
            dist == 0 -> if (phase < 2) '≡' else '='
            dist == 1 -> when (phase) { 0 -> '='; 1 -> '≡'; 2 -> '-'; else -> '=' }
            else -> when (phase) { 0 -> '-'; 1 -> '·'; 2 -> '-'; else -> ' ' }
        }
        val colour = when (dist) {
            0 -> Rgb(150, 210, 255)
            1 -> Rgb(255, 190, 110)
            else -> Rgb(200, 120, 70)
        }
        p.put(x, y, glyph, colour, null, if (dist == 0) Attr.BOLD else Attr.NONE)
    }
}

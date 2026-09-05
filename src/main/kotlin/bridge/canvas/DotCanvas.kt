package bridge.canvas

/**
 * A canvas of dots finer than the cell grid, for plotting lines, paths and curves. Two
 * resolutions: braille, 2×4 dots per cell, or half blocks, 1×2 dots per cell, for a font without
 * the braille block. Dots are set in dot coordinates, then [paint] composes glyphs.
 */
class DotCanvas(val cellsW: Int, val cellsH: Int, val mode: Mode = Mode.BRAILLE) {
    enum class Mode(val dotsX: Int, val dotsY: Int) { BRAILLE(2, 4), HALF(1, 2) }

    val dotsW: Int = cellsW * mode.dotsX
    val dotsH: Int = cellsH * mode.dotsY
    private val bits = IntArray(cellsW * cellsH)
    private val colours = IntArray(cellsW * cellsH)

    fun set(dx: Int, dy: Int, colour: Rgb) {
        if (dx < 0 || dy < 0 || dx >= dotsW || dy >= dotsH) return
        val cx = dx / mode.dotsX
        val cy = dy / mode.dotsY
        val i = cy * cellsW + cx
        val bit = when (mode) {
            Mode.BRAILLE -> BRAILLE_BITS[(dy % 4) * 2 + (dx % 2)]
            Mode.HALF -> if (dy % 2 == 0) 2 else 1
        }
        bits[i] = bits[i] or bit
        colours[i] = colour.packed
    }

    /** A straight line of dots, Bresenham. */
    fun line(x0: Int, y0: Int, x1: Int, y1: Int, colour: Rgb) {
        var x = x0
        var y = y0
        val dx = kotlin.math.abs(x1 - x0)
        val dy = -kotlin.math.abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx + dy
        while (true) {
            set(x, y, colour)
            if (x == x1 && y == y1) break
            val e2 = 2 * err
            if (e2 >= dy) { err += dy; x += sx }
            if (e2 <= dx) { err += dx; y += sy }
        }
    }

    /** Glyph for cell ([cx], [cy]), or null when no dot is set there. */
    fun glyphAt(cx: Int, cy: Int): Char? {
        val b = bits[cy * cellsW + cx]
        if (b == 0) return null
        return when (mode) {
            Mode.BRAILLE -> (0x2800 + b).toChar()
            Mode.HALF -> Glyphs.HALF[b]
        }
    }

    /** Writes the set cells onto [p] with their top-left at ([x], [y]); empty cells are left alone. */
    fun paint(p: Painter, x: Int, y: Int) {
        for (cy in 0 until cellsH) for (cx in 0 until cellsW) {
            val g = glyphAt(cx, cy) ?: continue
            p.put(x + cx, y + cy, g, Rgb(colours[cy * cellsW + cx]))
        }
    }

    companion object {
        /** Braille dot bits by (row * 2 + column): dots 1,4 / 2,5 / 3,6 / 7,8. */
        private val BRAILLE_BITS = intArrayOf(0x01, 0x08, 0x02, 0x10, 0x04, 0x20, 0x40, 0x80)
    }
}

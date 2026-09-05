package bridge.canvas

import bridge.glyphs.Palette

/**
 * A canvas of coloured half-cells: two vertical pixels per cell, each its own 24-bit colour,
 * drawn with `▀` and `▄` using the foreground for one half and the background for the other.
 * Since a cell is about twice as tall as it is wide, a half-cell is nearly square, which is what
 * makes round planets round. Unset pixels stay transparent.
 */
class HalfCanvas(val width: Int, val height: Int) {
    /** Pixel rows: twice the cell rows. */
    val rows: Int = height * 2
    private val px = IntArray(width * rows) { -1 }

    fun set(x: Int, y: Int, colour: Rgb) {
        if (x < 0 || y < 0 || x >= width || y >= rows) return
        px[y * width + x] = colour.packed
    }

    fun get(x: Int, y: Int): Rgb? {
        if (x < 0 || y < 0 || x >= width || y >= rows) return null
        val v = px[y * width + x]
        return if (v < 0) null else Rgb(v)
    }

    /** Blends [colour] over the pixel at [alpha], treating an unset pixel as [Palette.background]. */
    fun blend(x: Int, y: Int, colour: Rgb, alpha: Double) {
        val under = get(x, y) ?: Palette.background
        set(x, y, under.mix(colour, alpha))
    }

    fun paint(p: Painter, x: Int, y: Int) {
        for (cy in 0 until height) for (cx in 0 until width) {
            val upper = get(cx, cy * 2)
            val lower = get(cx, cy * 2 + 1)
            when {
                upper == null && lower == null -> Unit
                upper != null && lower != null -> p.put(x + cx, y + cy, '▀', upper, lower)
                upper != null -> p.put(x + cx, y + cy, '▀', upper, Palette.background)
                else -> p.put(x + cx, y + cy, '▄', lower!!, Palette.background)
            }
        }
    }
}

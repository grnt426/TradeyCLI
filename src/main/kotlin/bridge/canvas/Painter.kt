package bridge.canvas

import bridge.glyphs.Palette

/**
 * Draws into a [Surface] through a clip rectangle, with its own origin, so a view paints a panel's
 * interior at (0, 0) and cannot spill out of it. [sub] makes a painter for a rectangle inside this one.
 */
class Painter(val surface: Surface, val clip: Rect, private val ox: Int = 0, private val oy: Int = 0) {
    val width: Int get() = clip.w
    val height: Int get() = clip.h
    val bounds: Rect get() = Rect(0, 0, clip.w, clip.h)

    /** A painter for [r], given in this painter's coordinates. */
    fun sub(r: Rect): Painter {
        val abs = Rect(ox + r.x, oy + r.y, r.w, r.h).intersect(clip)
        return Painter(surface, abs, ox + r.x, oy + r.y)
    }

    fun put(x: Int, y: Int, ch: Char, fg: Rgb, bg: Rgb? = null, attrs: Int = Attr.NONE) {
        val ax = ox + x
        val ay = oy + y
        if (clip.contains(ax, ay)) surface.set(ax, ay, ch, fg, bg, attrs)
    }

    /** Writes [s] from ([x], [y]), cut at the clip. Returns the number of glyphs written. */
    fun text(x: Int, y: Int, s: CharSequence, fg: Rgb = Palette.text, bg: Rgb? = null, attrs: Int = Attr.NONE): Int {
        var n = 0
        for (i in s.indices) {
            val ax = ox + x + i
            if (ax >= clip.right) break
            if (clip.contains(ax, oy + y)) {
                surface.set(ax, oy + y, s[i], fg, bg, attrs)
                n++
            }
        }
        return n
    }

    /** [s] right-aligned so it ends at column [rightX] exclusive. */
    fun textRight(rightX: Int, y: Int, s: CharSequence, fg: Rgb = Palette.text, bg: Rgb? = null, attrs: Int = Attr.NONE) {
        text(rightX - s.length, y, s, fg, bg, attrs)
    }

    fun fill(r: Rect, ch: Char = ' ', fg: Rgb = Palette.text, bg: Rgb? = Palette.background, attrs: Int = Attr.NONE) {
        for (yy in r.y until r.bottom) for (xx in r.x until r.right) put(xx, yy, ch, fg, bg, attrs)
    }

    fun hline(x: Int, y: Int, len: Int, ch: Char = '─', fg: Rgb = Palette.border, bg: Rgb? = null) {
        for (i in 0 until len) put(x + i, y, ch, fg, bg)
    }

    fun vline(x: Int, y: Int, len: Int, ch: Char = '│', fg: Rgb = Palette.border, bg: Rgb? = null) {
        for (i in 0 until len) put(x, y + i, ch, fg, bg)
    }

    /** A rounded box on the edge of [r]. */
    fun box(r: Rect, fg: Rgb = Palette.border, bg: Rgb? = null) {
        if (r.w < 2 || r.h < 2) return
        hline(r.x + 1, r.y, r.w - 2, '─', fg, bg)
        hline(r.x + 1, r.bottom - 1, r.w - 2, '─', fg, bg)
        vline(r.x, r.y + 1, r.h - 2, '│', fg, bg)
        vline(r.right - 1, r.y + 1, r.h - 2, '│', fg, bg)
        put(r.x, r.y, '╭', fg, bg)
        put(r.right - 1, r.y, '╮', fg, bg)
        put(r.x, r.bottom - 1, '╰', fg, bg)
        put(r.right - 1, r.bottom - 1, '╯', fg, bg)
    }

    /**
     * A bordered panel with [title] set into the top edge. Returns the painter for the interior,
     * one cell in from the border on each side.
     */
    fun panel(r: Rect, title: String, focused: Boolean = false, hint: String? = null): Painter {
        val edge = if (focused) Palette.borderFocused else Palette.border
        fill(r)
        box(r, edge)
        if (title.isNotEmpty() && r.w > 4) {
            val shown = " " + title.take(r.w - 4) + " "
            text(r.x + 1, r.y, shown, if (focused) Palette.accent else Palette.title, null, Attr.BOLD)
        }
        if (hint != null && r.w > hint.length + 6) {
            text(r.right - 2 - hint.length - 1, r.y, " $hint ", Palette.textDim)
        }
        return sub(r.inset(1))
    }

    /** A horizontal gauge of [width] cells filled to [fraction] with block ramps. */
    fun gauge(x: Int, y: Int, width: Int, fraction: Double, fg: Rgb, track: Rgb = Palette.track) {
        val f = fraction.coerceIn(0.0, 1.0)
        val cells = f * width
        for (i in 0 until width) {
            val part = (cells - i).coerceIn(0.0, 1.0)
            val ch = when {
                part >= 0.999 -> '█'
                part <= 0.001 -> ' '
                else -> Glyphs.RAMP_H[(part * (Glyphs.RAMP_H.length - 1)).toInt()]
            }
            put(x + i, y, ch, fg, track)
        }
    }
}

/** Glyph vocabularies the painters share. */
object Glyphs {
    /** Filling left to right, one cell. */
    const val RAMP_H = " ▏▎▍▌▋▊▉█"

    /** Filling bottom to top, one cell: sparklines and vertical bars. */
    const val RAMP_V = " ▁▂▃▄▅▆▇█"

    /** Half-block pairs for two vertical dots per cell: [upper][lower] -> glyph. */
    val HALF = charArrayOf(' ', '▄', '▀', '█')
}

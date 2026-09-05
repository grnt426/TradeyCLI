package bridge.canvas

import bridge.glyphs.Palette

/**
 * One frame: a grid of cells, each a glyph with a foreground, a background and attributes. Stored
 * as parallel arrays so a frame is cheap to build and cheaper to diff. Every glyph is one column
 * wide by construction: the console only uses box drawing, blocks, shades, braille, geometric
 * shapes, arrows and Latin, never emoji or CJK, so column arithmetic never lies.
 */
class Surface(val width: Int, val height: Int, background: Rgb = Palette.background) {
    val chars = CharArray(width * height) { ' ' }
    val fg = IntArray(width * height) { Palette.text.packed }
    val bg = IntArray(width * height) { background.packed }
    val attrs = ByteArray(width * height)

    fun index(x: Int, y: Int): Int = y * width + x
    fun inBounds(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    fun set(x: Int, y: Int, ch: Char, fg: Rgb, bg: Rgb? = null, attrs: Int = Attr.NONE) {
        if (!inBounds(x, y)) return
        val i = index(x, y)
        chars[i] = ch
        this.fg[i] = fg.packed
        if (bg != null) this.bg[i] = bg.packed
        this.attrs[i] = attrs.toByte()
    }

    fun charAt(x: Int, y: Int): Char = chars[index(x, y)]
    fun fgAt(x: Int, y: Int): Rgb = Rgb(fg[index(x, y)])
    fun bgAt(x: Int, y: Int): Rgb = Rgb(bg[index(x, y)])
    fun attrsAt(x: Int, y: Int): Int = attrs[index(x, y)].toInt()

    /** True when the cell at [i] looks the same in both surfaces. Sizes must match. */
    fun sameCell(other: Surface, i: Int): Boolean =
        chars[i] == other.chars[i] && fg[i] == other.fg[i] && bg[i] == other.bg[i] && attrs[i] == other.attrs[i]

    /** The glyphs only, one line per row: what `--frame` prints and what tests compare. */
    fun toText(): String = buildString {
        for (y in 0 until height) {
            append(chars, y * width, width)
            if (y < height - 1) append('\n')
        }
    }

    /** Every cell painted in full, newline separated, for a pipe that understands colour but not cursor moves. */
    fun toAnsi(): String {
        val sb = StringBuilder()
        var last = Sgr.NONE
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = index(x, y)
                val style = Sgr(fg[i], bg[i], attrs[i].toInt())
                if (style != last) {
                    style.appendTo(sb)
                    last = style
                }
                sb.append(chars[i])
            }
            sb.append("\u001b[0m\n")
            last = Sgr.NONE
        }
        return sb.toString()
    }
}

/** One cell's style as a value, for comparing and emitting. */
data class Sgr(val fg: Int, val bg: Int, val attrs: Int) {
    fun appendTo(sb: StringBuilder) {
        sb.append("\u001b[0")
        if (attrs and Attr.BOLD != 0) sb.append(";1")
        if (attrs and Attr.DIM != 0) sb.append(";2")
        if (attrs and Attr.ITALIC != 0) sb.append(";3")
        if (attrs and Attr.UNDERLINE != 0) sb.append(";4")
        val f = Rgb(fg)
        val b = Rgb(bg)
        sb.append(";38;2;").append(f.r).append(';').append(f.g).append(';').append(f.b)
        sb.append(";48;2;").append(b.r).append(';').append(b.g).append(';').append(b.b)
        sb.append('m')
    }

    companion object {
        val NONE = Sgr(-1, -1, -1)
    }
}

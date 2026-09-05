package bridge.canvas

/**
 * Turns the difference between two frames into the fewest terminal writes: for each run of changed
 * cells, one cursor move, a style change only for the parts of the style that changed, then the
 * glyphs. The whole frame is wrapped in synchronized output (DEC private mode 2026) so a terminal
 * that understands it shows the frame at once; one that does not ignores the sequence.
 *
 * A first frame, or a frame of a different size, clears the screen and paints everything.
 */
object FrameDiff {
    private const val ESC = "\u001b["
    private const val SYNC_START = "\u001b[?2026h"
    private const val SYNC_END = "\u001b[?2026l"
    private const val RESET_AND_CLEAR = "\u001b[0m\u001b[2J"

    /** A gap of unchanged cells this short is written through rather than jumped over: the glyphs are cheaper than the move. */
    private const val WRITE_THROUGH = 2

    fun render(prev: Surface?, next: Surface): String {
        val sb = StringBuilder(if (prev == null) next.width * next.height * 4 else 1024)
        val full = prev == null || prev.width != next.width || prev.height != next.height
        sb.append(SYNC_START)
        if (full) sb.append(RESET_AND_CLEAR)
        val style = StyleTracker()
        val w = next.width
        val h = next.height
        // Where the terminal's cursor is after the last glyph, or -1 when unknown.
        var curX = -1
        var curY = -1
        for (y in 0 until h) {
            var x = 0
            while (x < w) {
                if (!changed(prev, next, x, y, full)) {
                    x++
                    continue
                }
                // Move: a short hop along the current row, or an absolute position.
                if (curY == y && curX in 0 until x) {
                    val gap = x - curX
                    if (gap <= WRITE_THROUGH && (curX until x).all { style.matches(next, next.index(it, y)) }) {
                        for (gx in curX until x) sb.append(next.chars[next.index(gx, y)])
                    } else {
                        sb.append(ESC).append(gap).append('C')
                    }
                } else {
                    sb.append(ESC).append(y + 1).append(';').append(x + 1).append('H')
                }
                while (x < w && changed(prev, next, x, y, full)) {
                    val j = next.index(x, y)
                    style.emit(sb, next.fg[j], next.bg[j], next.attrs[j].toInt())
                    sb.append(next.chars[j])
                    x++
                }
                curX = x
                curY = y
            }
        }
        sb.append("\u001b[0m").append(SYNC_END)
        return sb.toString()
    }

    /** The bottom-right cell is never written: some consoles scroll when it is. */
    private fun changed(prev: Surface?, next: Surface, x: Int, y: Int, full: Boolean): Boolean {
        if (y == next.height - 1 && x == next.width - 1) return false
        return full || !prev!!.sameCell(next, next.index(x, y))
    }

    /** Remembers what the terminal was last told, and sends only the difference. */
    private class StyleTracker {
        private var fg = -1
        private var bg = -1
        private var attrs = -1

        fun matches(s: Surface, i: Int): Boolean = s.fg[i] == fg && s.bg[i] == bg && s.attrs[i].toInt() == attrs

        fun emit(sb: StringBuilder, fg: Int, bg: Int, attrs: Int) {
            if (fg == this.fg && bg == this.bg && attrs == this.attrs) return
            if (attrs != this.attrs) {
                // Attributes can only be cleared with a reset, which also drops the colours.
                Sgr(fg, bg, attrs).appendTo(sb)
            } else {
                sb.append(ESC)
                var first = true
                if (fg != this.fg) {
                    val c = Rgb(fg)
                    sb.append("38;2;").append(c.r).append(';').append(c.g).append(';').append(c.b)
                    first = false
                }
                if (bg != this.bg) {
                    if (!first) sb.append(';')
                    val c = Rgb(bg)
                    sb.append("48;2;").append(c.r).append(';').append(c.g).append(';').append(c.b)
                }
                sb.append('m')
            }
            this.fg = fg
            this.bg = bg
            this.attrs = attrs
        }
    }
}

package bridge.scene

import bridge.canvas.Attr
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.glyphs.Palette

/** A line of a text block: the text, its colour, and whether it is set bold. */
data class Line(val text: String, val tone: Rgb = Palette.text, val bold: Boolean = false)

/**
 * Toned lines, word-wrapped to the width, continuation lines indented by two. Lines that do not
 * fit are dropped from the bottom, so the block never grows past its panel.
 */
class TextBlock(private val lines: () -> List<Line>, private val empty: String = "nothing yet") : Widget() {
    override fun paint(p: Painter, focused: Boolean, t: Double) {
        val all = lines()
        if (all.isEmpty()) {
            p.text(0, 0, empty, Palette.textDim)
            return
        }
        var y = 0
        for (line in all) {
            if (y >= p.height) break
            for ((i, part) in wrap(line.text, p.width).withIndex()) {
                if (y >= p.height) break
                p.text(if (i == 0) 0 else 2, y++, part, line.tone, null, if (line.bold) Attr.BOLD else Attr.NONE)
            }
        }
    }

    companion object {
        /** Word-wraps [text] to [width]; continuation lines are two shorter to leave room for their indent. */
        fun wrap(text: String, width: Int): List<String> {
            if (width <= 2) return listOf(text.take(width.coerceAtLeast(0)))
            val out = mutableListOf<String>()
            var current = StringBuilder()
            for (word in text.split(' ')) {
                val limit = if (out.isEmpty()) width else width - 2
                if (current.isNotEmpty() && current.length + 1 + word.length > limit) {
                    out += current.toString()
                    current = StringBuilder()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(word.take(if (out.isEmpty()) width else width - 2))
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }
    }
}

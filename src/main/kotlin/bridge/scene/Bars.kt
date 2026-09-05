package bridge.scene

import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.glyphs.Palette

/** One bar: a label, the number shown at the right, and the bar's length as a fraction of the longest. */
data class Bar(val label: String, val value: String, val fraction: Double, val tone: Rgb = Palette.info)

/** Labelled horizontal bars, longest first as given, each one row: label, bar, value. */
class Bars(private val bars: () -> List<Bar>, private val labelWidth: Int = 14, private val empty: String = "nothing yet") : Widget() {
    override fun paint(p: Painter, focused: Boolean, t: Double) {
        val all = bars()
        if (all.isEmpty()) {
            p.text(0, 0, empty, Palette.textDim)
            return
        }
        val valueWidth = all.maxOf { it.value.length }.coerceAtLeast(4)
        val label = labelWidth.coerceAtMost((p.width / 2).coerceAtLeast(4))
        val barX = label + 1
        val barW = (p.width - barX - valueWidth - 1).coerceAtLeast(1)
        all.take(p.height).forEachIndexed { y, b ->
            p.text(0, y, b.label.take(label), Palette.text)
            p.gauge(barX, y, barW, b.fraction, b.tone)
            p.textRight(p.width, y, b.value, Palette.textDim)
        }
    }
}

package bridge.scene

import bridge.Format
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.tty.Input
import java.time.Instant

/** One line of a feed: when, what, and how it is coloured. */
data class FeedLine(val at: Instant, val text: String, val tone: Rgb)

/**
 * Lines with their age, newest at the bottom, cut to the width. The wheel scrolls back into the
 * past; anything new snaps the view back to the bottom unless the reader has scrolled up.
 */
class Feed(private val lines: () -> List<FeedLine>, private val now: () -> Instant) : Widget() {
    private var back = 0

    override fun paint(p: Painter, focused: Boolean, t: Double) {
        val all = lines()
        val h = p.height
        if (all.isEmpty()) {
            p.text(0, 0, "nothing yet", bridge.glyphs.Palette.textDim)
            return
        }
        back = back.coerceIn(0, (all.size - h).coerceAtLeast(0))
        val end = all.size - back
        val shown = all.subList((end - h).coerceAtLeast(0), end)
        val top = h - shown.size
        val at = now()
        shown.forEachIndexed { i, line ->
            val age = Format.age(line.at, at).padStart(4)
            p.text(0, top + i, age, bridge.glyphs.Palette.textDim)
            p.text(5, top + i, line.text.take((p.width - 5).coerceAtLeast(0)), line.tone)
        }
        if (back > 0) p.textRight(p.width, 0, "↑$back", bridge.glyphs.Palette.warn)
    }

    override fun onMouse(m: Input.Mouse, x: Int, y: Int): Boolean {
        when {
            m.wheelUp -> back += 3
            m.wheelDown -> back = (back - 3).coerceAtLeast(0)
            else -> return false
        }
        return true
    }
}

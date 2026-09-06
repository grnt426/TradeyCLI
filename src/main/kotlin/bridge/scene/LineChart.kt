package bridge.scene

import bridge.Format
import bridge.canvas.DotCanvas
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.glyphs.Palette
import java.time.Duration
import java.time.Instant

/** One line on a [LineChart]: timestamped values with a colour and a legend label. */
data class Series(val label: String, val colour: Rgb, val points: List<Pair<Instant, Double>>)

/**
 * Several series over time on one braille canvas, the value range on the left, the time span on
 * the bottom row, and a legend underneath. Series are drawn in order, so the last is on top.
 */
object LineChart {
    /** Distinct hues for series that have no colour of their own. */
    val HUES = listOf(Rgb(92, 160, 236), Rgb(84, 200, 124), Rgb(232, 190, 72), Rgb(222, 82, 72), Rgb(200, 128, 240), Rgb(120, 200, 200), Rgb(236, 140, 100))

    fun paint(p: Painter, series: List<Series>, now: Instant, mode: DotCanvas.Mode, yLabel: (Double) -> String = { Format.compact(it.toLong()) }) {
        val live = series.filter { it.points.isNotEmpty() }
        if (live.isEmpty()) {
            p.text(0, 0, "no history yet", Palette.textDim)
            return
        }
        val labelWidth = 7
        val legendRows = if (p.height >= 6) 1 else 0
        val chartW = p.width - labelWidth - 1
        val chartH = p.height - 1 - legendRows
        if (chartW < 6 || chartH < 2) return
        val canvas = DotCanvas(chartW, chartH, mode)
        val start = live.minOf { s -> s.points.first().first }
        val span = Duration.between(start, now).coerceAtLeast(Duration.ofMinutes(1))
        val min = live.minOf { s -> s.points.minOf { it.second } }
        val max = live.maxOf { s -> s.points.maxOf { it.second } }
        val pad = ((max - min) * 0.08).coerceAtLeast(1.0)
        val lo = min - pad
        val hi = max + pad
        fun dx(at: Instant) = ((Duration.between(start, at).toMillis().toDouble() / span.toMillis()) * (canvas.dotsW - 1)).toInt().coerceIn(0, canvas.dotsW - 1)
        fun dy(v: Double) = ((hi - v) / (hi - lo) * (canvas.dotsH - 1)).toInt().coerceIn(0, canvas.dotsH - 1)
        for (s in live) {
            var prev: Pair<Int, Int>? = null
            for ((at, v) in s.points.sortedBy { it.first }) {
                val x = dx(at)
                val y = dy(v)
                prev?.let { (px, py) -> canvas.line(px, py, x, y, s.colour) } ?: canvas.set(x, y, s.colour)
                prev = x to y
            }
            // A last flat segment to now, so a line does not stop short of the right edge.
            prev?.let { (px, py) -> canvas.line(px, py, canvas.dotsW - 1, py, s.colour) }
        }
        canvas.paint(p, labelWidth + 1, 0)
        p.textRight(labelWidth, 0, yLabel(hi), Palette.textDim)
        if (chartH >= 4) p.textRight(labelWidth, chartH / 2, yLabel((hi + lo) / 2), Palette.textDim)
        p.textRight(labelWidth, chartH - 1, yLabel(lo), Palette.textDim)
        p.text(labelWidth + 1, chartH, "-${Format.span(span)}", Palette.textDim)
        p.textRight(p.width, chartH, "now", Palette.textDim)
        if (legendRows > 0) {
            var x = 0
            for (s in live) {
                val item = "■ ${s.label}"
                if (x + item.length > p.width) break
                p.put(x, chartH + 1, '■', s.colour)
                p.text(x + 2, chartH + 1, s.label, Palette.text)
                x += item.length + 2
            }
        }
    }
}

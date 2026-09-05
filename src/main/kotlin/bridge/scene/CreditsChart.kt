package bridge.scene

import behaviour.decisions.CreditPoint
import behaviour.decisions.CreditsTrend
import bridge.Format
import bridge.canvas.DotCanvas
import bridge.canvas.Painter
import bridge.glyphs.Palette
import java.time.Instant

/**
 * The bank over the last hours as a braille line, the trend's projection continuing it as a
 * dotted line in the warning colour, the range on the left, and the numbers underneath: the bank
 * now, the rate, and where the projection ends.
 */
class CreditsChart(
    private val history: () -> List<CreditPoint>,
    private val now: () -> Instant,
    private val dots: () -> DotCanvas.Mode,
) : Widget() {
    override fun paint(p: Painter, focused: Boolean, t: Double) {
        val points = history()
        if (points.isEmpty()) {
            p.text(0, 0, "no credits history yet; it fills as the bot trades", Palette.textDim)
            return
        }
        val labelWidth = 7
        val chartW = p.width - labelWidth - 1
        val chartH = p.height - 2
        if (chartW < 8 || chartH < 2) return
        val mode = dots()
        val canvas = DotCanvas(chartW, chartH, mode)
        val projection = canvas.dotsW / 4
        val graph = CreditsTrend.graph(points, now(), historyColumns = canvas.dotsW - projection, projectionColumns = projection)
        // Anchored at zero with a round ceiling, so the scale only moves when the bank crosses a round number.
        val ceiling = niceCeiling(graph.max)
        fun dy(v: Long): Int = ((ceiling - v).toDouble() / ceiling * (canvas.dotsH - 1)).toInt().coerceIn(0, canvas.dotsH - 1)

        var prev: Pair<Int, Int>? = null
        graph.columns.forEachIndexed { dx, c ->
            val v = c.value ?: run { prev = null; return@forEachIndexed }
            val y = dy(v)
            if (c.projected) {
                if (dx % 2 == 0) canvas.set(dx, y, Palette.warn)
            } else {
                prev?.let { (px, py) -> canvas.line(px, py, dx, y, Palette.good) } ?: canvas.set(dx, y, Palette.good)
            }
            prev = dx to y
        }
        canvas.paint(p, labelWidth + 1, 0)

        p.textRight(labelWidth, 0, Format.compact(ceiling), Palette.textDim)
        if (chartH >= 5) p.textRight(labelWidth, chartH / 2, Format.compact(ceiling / 2), Palette.textDim)
        if (chartH > 2) p.textRight(labelWidth, chartH - 1, "0", Palette.textDim)
        // The split between what happened and what is projected.
        val splitX = labelWidth + 1 + (canvas.dotsW - projection) / mode.dotsX
        p.vline(splitX, 0, chartH, '┆', Palette.track)

        val bucketMinutes = graph.projectionSpan.toMinutes()
        val historyMinutes = graph.columns.count { !it.projected } * graph.projectionSpan.toMinutes() / projection.coerceAtLeast(1)
        p.text(labelWidth + 1, chartH, "-${Format.span(java.time.Duration.ofMinutes(historyMinutes))}", Palette.textDim)
        p.text(splitX - 1, chartH, "now", Palette.textDim)
        p.textRight(p.width, chartH, "+${bucketMinutes}m", Palette.textDim)

        val trend = graph.trend
        val rate = (if (trend.perHour >= 0) "+" else "") + Format.compact(trend.perHour.toLong()) + "/h"
        var x = 0
        x += p.text(x, chartH + 1, Format.credits(trend.nowValue.toLong()), Palette.textBright) + 2
        x += p.text(x, chartH + 1, rate, if (trend.perHour >= 0) Palette.good else Palette.bad) + 2
        p.text(x, chartH + 1, "→ ~${Format.compact(graph.projectedEnd.toLong())} in ${bucketMinutes}m", Palette.warn)
    }

    companion object {
        private val STEPS = doubleArrayOf(1.0, 1.5, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0)

        /** The smallest of 1, 1.5, 2, 3, 4, 5, 6, 8 times a power of ten that is at least [v]. */
        fun niceCeiling(v: Long): Long {
            if (v <= 0) return 1
            val magnitude = Math.pow(10.0, Math.floor(Math.log10(v.toDouble())))
            for (step in STEPS) {
                val c = step * magnitude
                if (c >= v) return c.toLong()
            }
            return (10 * magnitude).toLong()
        }
    }
}

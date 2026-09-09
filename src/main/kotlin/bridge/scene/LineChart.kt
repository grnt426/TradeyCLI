package bridge.scene

import bridge.Format
import bridge.canvas.DotCanvas
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.glyphs.Palette
import java.time.Duration
import java.time.Instant
import kotlin.math.log10
import kotlin.math.pow

/** One line on a [LineChart]: timestamped values with a colour and a legend label. */
data class Series(val label: String, val colour: Rgb, val points: List<Pair<Instant, Double>>)

/**
 * Several series over time on one braille canvas, the value range on the left, the time span on
 * the bottom row, and a legend underneath. Series are drawn in order, so the last is on top.
 */
object LineChart {
    /** Distinct hues for series that have no colour of their own. */
    val HUES = listOf(Rgb(92, 160, 236), Rgb(84, 200, 124), Rgb(232, 190, 72), Rgb(222, 82, 72), Rgb(200, 128, 240), Rgb(120, 200, 200), Rgb(236, 140, 100))

    /** Values under this draw on the bottom edge of a log chart: a thousand credits, the smallest amount worth a line. */
    const val LOG_FLOOR = 1_000.0

    /**
     * Where a painted chart put things, so a caller can turn a click into a time and a value and
     * draw its own marks. Cells are relative to the painter the chart was given; the canvas
     * starts at column [left]. [lo] and [hi] are in scale units: log10 of credits on a log chart.
     */
    class Geometry(val start: Instant, val span: Duration, val lo: Double, val hi: Double, val log: Boolean, val left: Int, val chartW: Int, val chartH: Int, val mode: DotCanvas.Mode) {
        fun scale(v: Double): Double = if (log) log10(v.coerceAtLeast(LOG_FLOOR)) else v
        fun unscale(y: Double): Double = if (log) 10.0.pow(y) else y
        /** The time under column [col] of the painter, clamped to the span. */
        fun timeAt(col: Int): Instant = start.plusMillis((span.toMillis() * ((col - left).toDouble() / (chartW - 1).coerceAtLeast(1))).toLong().coerceIn(0, span.toMillis()))
        /** The painter row a value falls on. */
        fun rowOf(v: Double): Int = ((hi - scale(v)) / (hi - lo) * (chartH * mode.dotsY - 1)).toInt().coerceIn(0, chartH * mode.dotsY - 1) / mode.dotsY
        fun dotX(at: Instant): Int = ((Duration.between(start, at).toMillis().toDouble() / span.toMillis()) * (chartW * mode.dotsX - 1)).toInt().coerceIn(0, chartW * mode.dotsX - 1)
        fun dotY(v: Double): Int = ((hi - scale(v)) / (hi - lo) * (chartH * mode.dotsY - 1)).toInt().coerceIn(0, chartH * mode.dotsY - 1)
        fun inCanvas(col: Int, row: Int): Boolean = col >= left && col < left + chartW && row in 0 until chartH
    }

    /**
     * [fromZero] anchors the bottom of the range at zero, for running totals that start there.
     * [log] draws values on a log scale from [LOG_FLOOR] up, so a small category's growth shows
     * beside a large one's, with a tick and a faint gridline at every power of ten so the scale
     * reads as what it is; the axis labels are credits either way. [legend] false leaves the
     * legend row out, for a caller that draws one legend for several charts. [marks] draws on the
     * dot canvas before the series, for a caller's crosshair. Returns where things went, or null
     * when there was nothing to draw.
     */
    fun paint(
        p: Painter, series: List<Series>, now: Instant, mode: DotCanvas.Mode,
        yLabel: (Double) -> String = { Format.compact(it.toLong()) },
        fromZero: Boolean = false, log: Boolean = false, legend: Boolean = true,
        marks: (DotCanvas, Geometry) -> Unit = { _, _ -> },
    ): Geometry? {
        val live = series.filter { it.points.isNotEmpty() }
        if (live.isEmpty()) {
            p.text(0, 0, "no history yet", Palette.textDim)
            return null
        }
        val labelWidth = 7
        val legendRows = if (legend && p.height >= 6) 1 else 0
        val chartW = p.width - labelWidth - 1
        val chartH = p.height - 1 - legendRows
        if (chartW < 6 || chartH < 2) return null
        val canvas = DotCanvas(chartW, chartH, mode)
        val start = live.minOf { s -> s.points.first().first }
        val span = Duration.between(start, now).coerceAtLeast(Duration.ofMinutes(1))
        // The scale's transform: identity, or log10 with everything under the floor pinned to it.
        fun tr(v: Double): Double = if (log) log10(v.coerceAtLeast(LOG_FLOOR)) else v
        val min = live.minOf { s -> s.points.minOf { tr(it.second) } }
        val max = live.maxOf { s -> s.points.maxOf { tr(it.second) } }
        val pad = ((max - min) * 0.08).coerceAtLeast(if (log) 0.02 else 1.0)
        val lo = when {
            log -> tr(LOG_FLOOR)
            fromZero -> minOf(0.0, min)
            else -> min - pad
        }
        val hi = max + pad
        val g = Geometry(start, span, lo, hi, log, labelWidth + 1, chartW, chartH, mode)

        // Ticks: every power of ten on a log chart, gridlined so the spacing shows; else top, middle, bottom.
        val ticks = ArrayList<Pair<Int, String>>()
        if (log) {
            val grid = Palette.border.mix(Palette.background, 0.5)
            var decade = Math.ceil(lo).toInt()
            val rowsPerDecade = chartH / (hi - lo).coerceAtLeast(0.5)
            val every = if (rowsPerDecade >= 2.0) 1 else 2
            while (decade <= hi) {
                val y = g.dotY(10.0.pow(decade))
                if (decade % every == 0) {
                    canvas.line(0, y, canvas.dotsW - 1, y, grid, stride = 3)
                    ticks += (y / mode.dotsY) to yLabel(10.0.pow(decade))
                }
                decade++
            }
            ticks += (chartH - 1) to yLabel(LOG_FLOOR)
        } else {
            ticks += 0 to yLabel(hi)
            if (chartH >= 4) ticks += (chartH / 2) to yLabel((hi + lo) / 2)
            ticks += (chartH - 1) to yLabel(lo)
        }
        marks(canvas, g)
        for (s in live) {
            var prev: Pair<Int, Int>? = null
            for ((at, v) in s.points.sortedBy { it.first }) {
                val x = g.dotX(at)
                val y = g.dotY(v)
                prev?.let { (px, py) -> canvas.line(px, py, x, y, s.colour) } ?: canvas.set(x, y, s.colour)
                prev = x to y
            }
            // A last flat segment to now, so a line does not stop short of the right edge.
            prev?.let { (px, py) -> canvas.line(px, py, canvas.dotsW - 1, py, s.colour) }
        }
        canvas.paint(p, labelWidth + 1, 0)
        val labelled = HashSet<Int>()
        for ((row, text) in ticks) if (labelled.add(row)) p.textRight(labelWidth, row, text, Palette.textDim)
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
        return g
    }
}

package bridge.scene

import bridge.Format
import bridge.MoneyEvent
import bridge.MoneyFlows
import bridge.canvas.Attr
import bridge.canvas.DotCanvas
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.glyphs.Palette
import bridge.tty.Input
import java.time.Duration
import java.time.Instant

/**
 * How each category's credits have grown: one running-total line per category on a [LineChart],
 * on a log scale so a small category's curve shows beside a large one's. Colours come from the
 * caller so several charts can share one legend. The series are rebuilt only when the events
 * change, since the ledger runs to thousands of entries and the screen paints twenty times a second.
 *
 * A log scale is hard to read off by eye, so a click on the canvas probes it: the curve nearest
 * the click is picked, a crosshair marks the time and that curve's value then, the value is
 * written on the axis and the readout names the category, the value and how long ago. The probe
 * keeps its time as the chart grows; a click on the axis clears it.
 */
class GrowthChart(
    private val events: () -> List<MoneyEvent>,
    private val now: () -> Instant,
    private val dots: () -> DotCanvas.Mode,
    private val colour: (String) -> Rgb,
    private val empty: String = "nothing yet",
) : Widget() {
    private var builtFor: Pair<Int, Instant?>? = null
    private var series: List<Series> = emptyList()
    private var geometry: LineChart.Geometry? = null

    private class Probe(val at: Instant, val label: String)
    private var probe: Probe? = null

    override fun paint(p: Painter, focused: Boolean, t: Double) {
        val all = events()
        val stamp = all.size to all.lastOrNull()?.at
        if (stamp != builtFor) {
            builtFor = stamp
            series = MoneyFlows.cumulative(all).entries.map { (category, points) ->
                Series(category, colour(category), points.map { (at, total) -> at to total.toDouble() })
            }
        }
        if (series.isEmpty()) {
            p.text(0, 0, empty, Palette.textDim)
            return
        }
        val pr = probe
        val picked = pr?.let { pk -> series.firstOrNull { it.label == pk.label } }
        val value = if (pr != null && picked != null) valueAt(picked, pr.at) else null
        // Largest category first, so the smaller ones, drawn after it, lie on top rather than under it.
        geometry = LineChart.paint(p, series, now(), dots(), log = true, legend = false) { canvas, g ->
            if (pr != null && picked != null && value != null) {
                val cross = picked.colour.mix(Palette.background, 0.45)
                val x = g.dotX(pr.at)
                val y = g.dotY(value)
                canvas.line(x, 0, x, canvas.dotsH - 1, cross, stride = 2)
                canvas.line(0, y, canvas.dotsW - 1, y, cross, stride = 2)
            }
        }
        val g = geometry ?: return
        if (pr != null && picked != null && value != null) {
            val row = g.rowOf(value)
            p.textRight(g.left - 1, row, Format.compact(value.toLong()), picked.colour, null, Attr.BOLD)
            val readout = "■ ${picked.label} ${Format.compact(value.toLong())} · ${Format.span(Duration.between(pr.at, now()))} ago"
            p.text(g.left + 1, 0, readout.take((g.chartW - 2).coerceAtLeast(0)), picked.colour, Palette.background, Attr.BOLD)
        }
    }

    /** A running total's value at [at]: the last step at or before it, zero before the first. */
    private fun valueAt(s: Series, at: Instant): Double = s.points.lastOrNull { !it.first.isAfter(at) }?.second ?: 0.0

    override fun onMouse(m: Input.Mouse, x: Int, y: Int): Boolean {
        if (!m.left) return false
        val g = geometry ?: return false
        if (!g.inCanvas(x, y)) {
            if (probe != null) { probe = null; return true }
            return false
        }
        val at = g.timeAt(x)
        // The curve whose value at that time lies nearest the click, in rows.
        val nearest = series.minByOrNull { s -> kotlin.math.abs(g.rowOf(valueAt(s, at)) - y) } ?: return false
        probe = Probe(at, nearest.label)
        return true
    }
}

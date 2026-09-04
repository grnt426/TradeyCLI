package behaviour.decisions

import java.time.Duration
import java.time.Instant

/** The bank at one moment. */
data class CreditPoint(val at: Instant, val credits: Long)

/** A straight line through recent history: credits per hour, and where the line sits now. */
data class Trend(val perHour: Double, val nowValue: Double, val points: Int) {
    fun at(now: Instant, then: Instant): Double = nowValue + perHour * (then.toEpochMilli() - now.toEpochMilli()) / 3_600_000.0
}

/** One column of the credits graph: the bank at the end of its bucket, or a projection. */
data class GraphColumn(val at: Instant, val value: Long?, val projected: Boolean)

data class CreditsGraph(
    val columns: List<GraphColumn>,
    val min: Long,
    val max: Long,
    val bucket: Duration,
    val trend: Trend,
    val now: Instant,
) {
    /** Height in [rows] cells, with eighths: 0 means empty, 8 * rows means full. */
    fun eighths(value: Long, rows: Int): Int {
        if (max == min) return rows * 4
        return ((value - min).toDouble() / (max - min) * rows * 8).toInt().coerceIn(0, rows * 8)
    }

    val historySpan: Duration get() = bucket.multipliedBy(columns.count { !it.projected }.toLong())
    val projectionSpan: Duration get() = bucket.multipliedBy(columns.count { it.projected }.toLong())
    val projectedEnd: Double get() = columns.lastOrNull { it.projected }?.value?.toDouble() ?: trend.nowValue

    /** The value band a row covers, in eighths from the bottom: row 0 is the top. */
    fun bandFloor(row: Int, rows: Int): Int = (rows - 1 - row) * 8

    /** What a column shows on [row]: 8 for full, 0 for empty, else the partial block at the top of the bar. */
    fun glyphIndex(value: Long, row: Int, rows: Int): Int {
        val eighths = eighths(value, rows)
        val floor = bandFloor(row, rows)
        return when {
            eighths >= floor + 8 -> 8
            eighths <= floor -> 0
            else -> eighths - floor
        }
    }

    /** Y-axis label for [row], or blank: the max at the top, the min at the bottom, the middle in between. */
    fun label(row: Int, rows: Int): String = when (row) {
        0 -> CreditsTrend.compact(max)
        rows - 1 -> CreditsTrend.compact(min)
        rows / 2 -> CreditsTrend.compact((min + max) / 2)
        else -> ""
    }

    /** The time axis under the bars: a tick every [every] columns, a bar where the projection starts. */
    fun axis(every: Int = 6): String {
        val boundary = columns.indexOfFirst { it.projected }
        return columns.indices.joinToString("") { i ->
            when {
                i == boundary -> "|"
                i % every == 0 -> "+"
                else -> "-"
            }
        }
    }

    /** Labels under the axis: how far back the left edge is, "now" at the boundary, how far ahead the right edge is. */
    fun axisLabels(): String {
        val history = columns.count { !it.projected }
        val left = "-${historySpan.toMinutes()}m"
        val right = "+${projectionSpan.toMinutes()}m"
        val sb = StringBuilder(" ".repeat(columns.size))
        sb.replace(0, left.length, left)
        val nowAt = (history - 1).coerceAtLeast(0)
        sb.replace(nowAt, minOf(nowAt + 3, sb.length), "now")
        sb.replace(sb.length - right.length, sb.length, right)
        return sb.toString()
    }
}

object CreditsTrend {

    /** Least-squares line through the points inside [window], or flat when there are fewer than two. */
    fun trend(history: List<CreditPoint>, now: Instant, window: Duration = Duration.ofMinutes(30)): Trend {
        val since = now.minus(window)
        val recent = history.filter { !it.at.isBefore(since) }.sortedBy { it.at }
        val latest = history.maxByOrNull { it.at }?.credits?.toDouble() ?: 0.0
        if (recent.size < 2) return Trend(0.0, latest, recent.size)
        val xs = recent.map { (it.at.toEpochMilli() - now.toEpochMilli()) / 3_600_000.0 }
        val ys = recent.map { it.credits.toDouble() }
        val meanX = xs.average()
        val meanY = ys.average()
        val varX = xs.sumOf { (it - meanX) * (it - meanX) }
        if (varX == 0.0) return Trend(0.0, latest, recent.size)
        val slope = xs.zip(ys).sumOf { (x, y) -> (x - meanX) * (y - meanY) } / varX
        val intercept = meanY - slope * meanX
        return Trend(slope, intercept, recent.size)
    }

    /**
     * The graph: [historyColumns] buckets of the past, each holding the last bank value seen in
     * it (carried forward through quiet buckets), then [projectionColumns] buckets of the trend.
     */
    fun graph(
        history: List<CreditPoint>,
        now: Instant,
        bucket: Duration = Duration.ofSeconds(90),
        historyColumns: Int = 36,
        projectionColumns: Int = 16,
        window: Duration = Duration.ofMinutes(30),
    ): CreditsGraph {
        val trend = trend(history, now, window)
        val sorted = history.sortedBy { it.at }
        val start = now.minus(bucket.multipliedBy(historyColumns.toLong()))
        var carried: Long? = sorted.lastOrNull { it.at.isBefore(start) }?.credits
        val columns = mutableListOf<GraphColumn>()
        for (i in 1..historyColumns) {
            val end = start.plus(bucket.multipliedBy(i.toLong()))
            sorted.lastOrNull { !it.at.isBefore(end.minus(bucket)) && it.at.isBefore(end) }?.let { carried = it.credits }
            columns += GraphColumn(end, carried, projected = false)
        }
        // The projection continues from the last real value at the trend's slope, so it joins the history without a step.
        val latest = sorted.lastOrNull()?.credits?.toDouble() ?: 0.0
        for (i in 1..projectionColumns) {
            val at = now.plus(bucket.multipliedBy(i.toLong()))
            val hours = (at.toEpochMilli() - now.toEpochMilli()) / 3_600_000.0
            columns += GraphColumn(at, (latest + trend.perHour * hours).toLong().coerceAtLeast(0), projected = true)
        }
        val values = columns.mapNotNull { it.value }
        val min = values.minOrNull() ?: 0
        val max = values.maxOrNull() ?: 0
        return CreditsGraph(columns, min, max, bucket, trend, now)
    }

    fun compact(credits: Long): String = when {
        credits >= 1_000_000_000 -> "%.2fB".format(credits / 1e9)
        credits >= 1_000_000 -> "%.2fM".format(credits / 1e6)
        credits >= 1_000 -> "%.0fk".format(credits / 1e3)
        else -> credits.toString()
    }

    fun compact(credits: Double): String = compact(credits.toLong())
}

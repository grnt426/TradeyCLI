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
    val projectedEnd: Double get() = trend.at(now, now.plus(projectionSpan))
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
        for (i in 1..projectionColumns) {
            val at = now.plus(bucket.multipliedBy(i.toLong()))
            columns += GraphColumn(at, trend.at(now, at).toLong().coerceAtLeast(0), projected = true)
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

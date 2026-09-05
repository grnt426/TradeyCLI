package bridge

import java.time.Duration
import java.time.Instant

/** Numbers and times the way the console shows them: short, aligned, no noise. */
object Format {
    fun credits(n: Long): String = "%,d".format(n)

    /** 12.3k, 1.2M: for cells with no room for the digits. */
    fun compact(n: Long): String {
        val a = kotlin.math.abs(n)
        val sign = if (n < 0) "-" else ""
        return when {
            a < 10_000 -> "$sign$a"
            a < 1_000_000 -> "%s%.1fk".format(sign, a / 1e3)
            a < 1_000_000_000 -> "%s%.2fM".format(sign, a / 1e6)
            else -> "%s%.2fG".format(sign, a / 1e9)
        }
    }

    /** 12s, 5m, 2h10, 3d: how long ago [then] was at [now]. */
    fun age(then: Instant, now: Instant): String = span(Duration.between(then, now))

    /** The same, for a duration, with "now" for nothing left. */
    fun span(d: Duration): String {
        val s = d.seconds
        return when {
            s <= 0 -> "now"
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m"
            s < 86400 -> "${s / 3600}h${(s % 3600 / 60).toString().padStart(2, '0')}"
            else -> "${s / 86400}d"
        }
    }

    /** m:ss for a countdown under an hour, h:mm:ss above. */
    fun clock(d: Duration): String {
        val s = d.seconds.coerceAtLeast(0)
        return if (s < 3600) "%d:%02d".format(s / 60, s % 60) else "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    }
}

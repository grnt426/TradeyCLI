package behaviour.decisions

import behaviour.BehaviourScope
import engine.Snapshot
import model.market.TradeSymbol
import model.market.TransactionType
import java.time.Duration
import java.time.Instant

/** One line of what the bot is up to, with how it should be coloured. */
data class Intent(val text: String, val tone: Tone) {
    enum class Tone { GOOD, NEUTRAL, WARN }
}

/**
 * What the automation is doing and aiming for, in plain sentences, from the plan, the ships'
 * phases, the recent transactions and the fleet goals. Pure: a snapshot in, lines out.
 */
object Intentions {

    /** Lets a test decide which process ids count as alive. */
    var processAlive: (Long) -> Boolean = plan.RunLock::processAlive

    fun describe(snapshot: Snapshot, now: Instant, trend: Trend = Trend(0.0, snapshot.agent?.credits?.toDouble() ?: 0.0, 0)): List<Intent> {
        val lines = mutableListOf<Intent>()
        val plan = snapshot.plan
        val credits = snapshot.agent?.credits ?: 0L

        // Who is driving
        val runner = snapshot.runner
        lines += when {
            runner == null -> Intent("Nobody is running the plan: start `TradeyCLI run` in a terminal", Intent.Tone.WARN)
            runner.isLive(now, processAlive) -> Intent("Plan driven by process ${runner.pid} since ${runner.started.atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}, heartbeat ${Duration.between(runner.heartbeat, now).seconds}s ago", Intent.Tone.GOOD)
            else -> Intent("Run process ${runner.pid} is gone (last heartbeat ${ago(runner.heartbeat, now)}); nothing is driving the plan", Intent.Tone.WARN)
        }

        // With a live driver a long silence is a long flight, not a dead run; without one, ten minutes is suspicious.
        val driverLive = runner != null && runner.isLive(now, processAlive)
        val stale = if (driverLive) Duration.ofHours(1) else STALE

        // What the ships are doing
        if (plan == null || plan.assignments.isEmpty()) {
            lines += Intent("No plan: nothing is assigned. `assign SHIP trade` in line mode, then `run`.", Intent.Tone.WARN)
        } else {
            plan.assignments.sortedBy { it.ship }.forEach { a ->
                val status = snapshot.shipStatus[a.ship]
                lines += when {
                    status == null -> Intent("${a.ship} ${a.describe()}: assigned, not running yet", Intent.Tone.WARN)
                    status.phase == "failed" -> Intent("${a.ship} ${status.behaviour}: FAILED ${status.detail}".take(120), Intent.Tone.WARN)
                    status.phase == "done" -> Intent("${a.ship} ${status.behaviour}: finished (${status.detail})", Intent.Tone.NEUTRAL)
                    Duration.between(status.since, now) > stale -> Intent("${a.ship} ${status.behaviour}: ${status.phase} ${status.detail} (last seen ${ago(status.since, now)}${if (driverLive) "" else "; is `run` still going?"})".take(140), Intent.Tone.WARN)
                    else -> Intent("${a.ship} ${status.behaviour}: ${status.phase} ${status.detail}".take(120), Intent.Tone.GOOD)
                }
            }
        }

        // How the trading is going
        val hourAgo = now.minus(Duration.ofHours(1))
        val recent = snapshot.recentTransactions.filter { Instant.parse(it.timestamp) >= hourAgo }
        val sales = recent.filter { it.type == TransactionType.SELL }
        val buys = recent.filter { it.type == TransactionType.PURCHASE && it.tradeSymbol != TradeSymbol.FUEL }
        val fuel = recent.filter { it.type == TransactionType.PURCHASE && it.tradeSymbol == TradeSymbol.FUEL }.sumOf { it.totalPrice.toLong() }
        if (sales.isNotEmpty() || buys.isNotEmpty()) {
            val loads = sales.map { "${it.shipSymbol}:${it.waypointSymbol}:${it.tradeSymbol}:${it.timestamp.take(16)}" }.toSet().size
            val net = sales.sumOf { it.totalPrice.toLong() } - buys.sumOf { it.totalPrice.toLong() } - fuel
            val goods = sales.groupBy { it.tradeSymbol }.entries.sortedByDescending { (_, t) -> t.sumOf { it.totalPrice } }.take(3).joinToString(", ") { it.key.name }
            lines += Intent("Trading: $loads loads sold in the last hour, net ${signed(net)} after fuel; mostly $goods", if (net > 0) Intent.Tone.GOOD else Intent.Tone.WARN)
        }
        if (trend.points >= 2) {
            lines += Intent("Bank ${format(credits)}, trending ${signed(trend.perHour.toLong())}/h over the last half hour", if (trend.perHour >= 0) Intent.Tone.GOOD else Intent.Tone.WARN)
        }

        // What it is saving for
        plan?.goals?.fleet?.forEach { goal ->
            val owned = goal.owned(snapshot.ships.values)
            if (owned >= goal.count) {
                lines += Intent("Fleet goal met: $owned of ${goal.describe()}", Intent.Tone.NEUTRAL)
                return@forEach
            }
            val yards = snapshot.shipyards.values.filter { it.sells(goal.type) }
            val price = yards.mapNotNull { it.priceOf(goal.type) }.minOrNull()
            val where = yards.joinToString(", ") { it.symbol }.ifEmpty { "no shipyard here sells it" }
            val next = "${goal.type.name.removePrefix("SHIP_")} #${owned + 1} of ${goal.count}"
            if (price == null) {
                lines += Intent("Saving for $next: price unknown until a ship docks at $where; keeping ${format(goal.reserve)} in reserve", Intent.Tone.NEUTRAL)
            } else {
                val needed = price + goal.reserve
                if (credits >= needed) {
                    lines += Intent("Ready to buy $next at ${format(price)}: waiting for a trader to dock at $where", Intent.Tone.GOOD)
                } else {
                    val short = needed - credits
                    val eta = if (trend.perHour > 0) " about ${minutes(short / trend.perHour)} at the current rate" else ""
                    lines += Intent("Saving for $next: ${format(credits)} of ${format(needed)} (${format(price)} plus ${format(goal.reserve)} reserve), ${format(short)} short;$eta", Intent.Tone.NEUTRAL)
                }
            }
        }
        plan?.goals?.credits?.let { target ->
            if (credits >= target) lines += Intent("Credits goal of ${format(target)} reached", Intent.Tone.GOOD)
            else lines += Intent("Credits goal ${format(target)}: ${format(credits)} so far" + (if (trend.perHour > 0) ", about ${minutes((target - credits) / trend.perHour)} away" else ""), Intent.Tone.NEUTRAL)
        }
        return lines
    }

    private val STALE: Duration = Duration.ofMinutes(10)

    fun format(n: Long): String = "%,d".format(n)
    private fun signed(n: Long): String = (if (n >= 0) "+" else "") + format(n)
    private fun ago(then: Instant, now: Instant): String = minutes(Duration.between(then, now).toMillis() / 3_600_000.0) + " ago"
    private fun minutes(hours: Double): String {
        val m = (hours * 60).toLong()
        return when {
            m < 1 -> "under a minute"
            m < 90 -> "$m min"
            else -> "%.1f h".format(hours)
        }
    }
}

package behaviour.decisions

import model.market.MarketTransaction
import model.market.TradeSymbol
import model.market.TransactionType
import plan.Chain
import java.time.Duration
import java.time.Instant
import kotlin.math.exp

/** One leg's tally in a chain's ledger. */
data class LegTally(val good: TradeSymbol, val from: String, val to: String, val bought: Int, val spent: Long, val sold: Int, val earned: Long) {
    val net: Long get() = earned - spent
}

/** A chain's books over a window. */
data class ChainLedger(
    val chain: Chain,
    val legs: List<LegTally>,
    val fuel: Long,
    /** Net credits per hour, recent hours weighted more (half-life one hour). */
    val smoothedPerHour: Double,
    /** Net credits per hour over the whole window, unweighted. */
    val rawPerHour: Double,
    val hoursObserved: Double,
) {
    val net: Long get() = legs.sumOf { it.net } - fuel
}

/** What the release policy decided and why. */
data class ChainVerdict(val keep: Boolean, val releaseShip: String?, val reason: String, val chainPerHour: Double, val alternativePerHour: Double)

/**
 * Chain accounting and the release policy. The policy is deliberately sluggish so releasing a
 * team cannot talk itself back into the chain: a wide dead band between keep and release, a
 * smoothed signal, a minimum tenure, one ship released at a time with a cooldown, and a
 * counterfactual fixed at enrolment rather than re-measured after the release changed it.
 */
object Chains {

    /** Keep while the chain pays at least this share of what the team would earn free; release below [releaseBelow]. */
    const val KEEP_ABOVE = 0.85
    const val RELEASE_BELOW = 0.65
    val MIN_TENURE: Duration = Duration.ofHours(2)
    val RELEASE_COOLDOWN: Duration = Duration.ofHours(1)
    val MIN_OBSERVATION: Duration = Duration.ofMinutes(45)
    private const val HALF_LIFE_HOURS = 1.0

    fun ledger(chain: Chain, transactions: List<MarketTransaction>, now: Instant, window: Duration = Duration.ofHours(6)): ChainLedger {
        val since = now.minus(window)
        val ours = transactions.filter { Instant.parse(it.timestamp) >= since }
        val legs = chain.legs.map { leg ->
            val buys = ours.filter { it.type == TransactionType.PURCHASE && it.tradeSymbol == leg.good && it.waypointSymbol == leg.from }
            val sells = ours.filter { it.type == TransactionType.SELL && it.tradeSymbol == leg.good && it.waypointSymbol == leg.to }
            LegTally(leg.good, leg.from, leg.to, buys.sumOf { it.units }, buys.sumOf { it.totalPrice.toLong() }, sells.sumOf { it.units }, sells.sumOf { it.totalPrice.toLong() })
        }
        val fuel = ours.filter { it.type == TransactionType.PURCHASE && it.tradeSymbol == TradeSymbol.FUEL }.sumOf { it.totalPrice.toLong() }
        val start = maxOf(since, chain.enrolled)
        val hours = (Duration.between(start, now).toMillis() / 3_600_000.0).coerceAtLeast(0.01)
        val net = legs.sumOf { it.net } - fuel
        // Weighted net: each transaction's signed value decays with age; divide by the weighted hours in the window.
        var weightedNet = 0.0
        ours.forEach { t ->
            val age = Duration.between(Instant.parse(t.timestamp), now).toMillis() / 3_600_000.0
            val w = exp(-age * Math.log(2.0) / HALF_LIFE_HOURS)
            weightedNet += w * (if (t.type == TransactionType.SELL) t.totalPrice else -t.totalPrice)
        }
        val weightedHours = (0 until (hours * 60).toInt()).sumOf { m -> exp(-(m / 60.0) * Math.log(2.0) / HALF_LIFE_HOURS) } / 60.0
        return ChainLedger(chain, legs, fuel, if (weightedHours > 0) weightedNet / weightedHours else 0.0, net / hours, hours)
    }

    /**
     * Keep, or release exactly one ship. [alternativePerHour] is the team's free-agent earning as
     * recorded when each ship was enrolled; it is never re-measured here, so a release cannot
     * lower the bar for the next evaluation.
     */
    fun verdict(ledger: ChainLedger, now: Instant, lastRelease: Instant?): ChainVerdict {
        val chain = ledger.chain
        val alternative = chain.baselines.values.sum()
        val rate = ledger.smoothedPerHour
        val ratio = if (alternative > 0) rate / alternative else Double.POSITIVE_INFINITY
        val tenure = Duration.between(chain.enrolled, now)
        fun keep(why: String) = ChainVerdict(true, null, why, rate, alternative)
        return when {
            chain.hold -> keep("held by hand; the policy is advisory")
            ledger.hoursObserved * 60 < MIN_OBSERVATION.toMinutes() -> keep("too early: ${(ledger.hoursObserved * 60).toInt()} min observed, policy starts at ${MIN_OBSERVATION.toMinutes()}")
            tenure < MIN_TENURE -> keep("inside the minimum tenure of ${MIN_TENURE.toHours()} h (${tenure.toMinutes()} min so far)")
            ratio >= KEEP_ABOVE -> keep("chain pays ${"%.0f".format(ratio * 100)}% of the team's free-agent rate; keep above ${(KEEP_ABOVE * 100).toInt()}%")
            ratio >= RELEASE_BELOW -> keep("in the dead band: ${"%.0f".format(ratio * 100)}% of the free-agent rate; release only below ${(RELEASE_BELOW * 100).toInt()}%")
            lastRelease != null && Duration.between(lastRelease, now) < RELEASE_COOLDOWN -> keep("below the release line, but a ship was released ${Duration.between(lastRelease, now).toMinutes()} min ago; one per ${RELEASE_COOLDOWN.toHours()} h")
            chain.ships.size <= 1 -> ChainVerdict(false, chain.ships.firstOrNull(), "below the release line and the team is down to one ship: dissolve", rate, alternative)
            else -> {
                // Release the ship whose free-agent baseline is highest: it has the most to gain elsewhere.
                val ship = chain.ships.maxByOrNull { chain.baselines[it] ?: 0.0 }
                ChainVerdict(false, ship, "${"%.0f".format(ratio * 100)}% of the free-agent rate is below ${(RELEASE_BELOW * 100).toInt()}%: release $ship, re-evaluate in ${RELEASE_COOLDOWN.toHours()} h", rate, alternative)
            }
        }
    }
}

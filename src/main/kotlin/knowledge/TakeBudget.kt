package knowledge

import model.market.ActivityLevel
import model.market.MarketTradeGood
import model.market.TradeGoodType
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * The rate limiter that keeps a producer healthy when several of our ships buy from it: a token
 * bucket per market and good, refilled at [MarketAssumptions.takeVolumesPerHour] for the stock
 * level last read, capped at [MarketAssumptions.takeBucketHours] of refill. A ship takes what the
 * bucket holds, never a whole hold just because it has one. The level is re-read every visit, so
 * the rate follows the stock up and down instead of stopping dead at LIMITED and restarting at
 * MODERATE, which is what three haulers did to F47 on 2026-09-05.
 */
class TakeBudget {
    private class Bucket(var tokens: Double, var at: Instant)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun perHour(listing: MarketTradeGood, rules: MarketAssumptions): Double =
        listing.tradeVolume * (rules.takeVolumesPerHour[listing.supply] ?: 0.0) *
            (if (listing.type == TradeGoodType.EXPORT && listing.activity == ActivityLevel.RESTRICTED) rules.restrictedTakeFactor else 1.0)

    private fun bucket(market: String, listing: MarketTradeGood, rules: MarketAssumptions, now: Instant): Bucket {
        val rate = perHour(listing, rules)
        val b = buckets.getOrPut("$market/${listing.symbol}") { Bucket(rate, now) }
        synchronized(b) {
            val hours = Duration.between(b.at, now).toMillis() / 3_600_000.0
            if (hours > 0) b.tokens = min(b.tokens + hours * rate, rate * rules.takeBucketHours)
            b.at = now
            // A stock that fell below the bucket's memory should not be drawn on from that memory.
            b.tokens = min(b.tokens, rate * rules.takeBucketHours)
        }
        return b
    }

    /** Units available now without consuming them. */
    fun available(market: String, listing: MarketTradeGood, rules: MarketAssumptions, now: Instant): Int =
        bucket(market, listing, rules, now).tokens.toInt()

    /** Takes up to [want] units from the bucket and returns how many were granted. */
    fun take(market: String, listing: MarketTradeGood, want: Int, rules: MarketAssumptions, now: Instant): Int {
        val b = bucket(market, listing, rules, now)
        synchronized(b) {
            val granted = min(want.toDouble(), b.tokens).toInt().coerceAtLeast(0)
            b.tokens -= granted
            return granted
        }
    }

    /** Gives back units that were granted but not bought. */
    fun refund(market: String, listing: MarketTradeGood, units: Int, rules: MarketAssumptions, now: Instant) {
        val b = bucket(market, listing, rules, now)
        synchronized(b) { b.tokens = min(b.tokens + units, perHour(listing, rules) * rules.takeBucketHours) }
    }
}

package sim

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The knobs of the simulated universe. Every value is an assumption about the live game until the
 * store's `extractions`, `transactions` and `market_prices` tables confirm or correct it; the
 * comments say where each number came from.
 */
data class SimRules(
    /** Cooldown after an extraction. Observed at 70 s on earlier resets. */
    val extractCooldown: Duration = 70.seconds,
    /** Cooldown after a survey. Same as extraction on earlier resets. */
    val surveyCooldown: Duration = 70.seconds,

    /** Units per extraction are drawn uniformly between strength times these two. Guess. */
    val yieldPerStrengthMin: Double = 1.0,
    val yieldPerStrengthMax: Double = 3.0,
    /** Extracting with a survey "procures a high amount", modelled as this multiplier. Guess. */
    val surveyYieldBonus: Double = 1.5,

    /** How long a survey stays usable. Guess from earlier resets (about an hour or two). */
    val surveyLifetime: Duration = 90.minutes,
    /** Extractions a survey survives by size. Guess. */
    val surveyBudgetSmall: Int = 5,
    val surveyBudgetModerate: Int = 10,
    val surveyBudgetLarge: Int = 20,

    /** Instability: recent extractions before an asteroid turns UNSTABLE, then CRITICAL_LIMIT, then STRIPPED. Guesses. */
    val unstableAfter: Double = 60.0,
    val criticalAfter: Double = 120.0,
    val strippedAfter: Double = 200.0,
    /** Recent-extraction count decays by this much per hour. Guess. */
    val recoveryPerHour: Double = 10.0,
    /** Yield multipliers while UNSTABLE and CRITICAL_LIMIT, and the chance an extraction is refused at CRITICAL_LIMIT. Guesses. */
    val unstableYield: Double = 0.75,
    val criticalYield: Double = 0.5,
    val criticalRefuseChance: Double = 0.3,
    /** Other players hammer the rocks near headquarters; this many extractions per hour land on each within [crowdedRadius]. Guess. */
    val foreignExtractionsPerHourNearHq: Double = 30.0,
    val crowdedRadius: Double = 60.0,

    /**
     * Price impact, observed live on 2026-09-04 (SHIP_PARTS, trade volume 6): selling one volume
     * dropped the import price 2.1% and each further volume about 30% more (2.7, 3.4, 4.4, 5.6,
     * 7.3%); buying one volume raised the export price 0.56% and each further one about 22% more.
     * [priceRecoveryPerHour] is still a guess; the return visit will show it.
     */
    val sellImpactPerVolume: Double = 0.021,
    val sellImpactGrowth: Double = 1.3,
    val buyImpactPerVolume: Double = 0.0056,
    val buyImpactGrowth: Double = 1.22,
    /**
     * Recovery, measured over five hours on 2026-09-04: prices we pushed did not come back on the
     * hour scale. Export prices we bought up kept rising with every load and never fell; the one
     * clear recovery was an export spike (2.5x) that came 42% of the way back in 107 minutes.
     * Import prices we sold into drifted lower still. Modelled as a slow drift toward the seed.
     */
    val priceRecoveryPerHour: Double = 0.2,
    /** Kept for the old linear model; the ranking uses its own [behaviour.decisions.TradingAssumptions]. */
    val priceImpactPerVolume: Double = 0.03,
    val priceFloor: Double = 0.15,
    val priceCeiling: Double = 3.0,
    /** Trade volume for goods at markets whose prices were never seen. */
    val defaultTradeVolume: Int = 20,

    /** Contracts: pay this multiple of the goods' sale value at the destination (the live offer paid 2.6x), with this long to deliver. */
    val contractPayMultiple: Double = 2.6,
    val contractDeadlineHours: Long = 24 * 7,
    /** Credits for charting a waypoint. Guess; nothing here is uncharted to measure. */
    val chartReward: Long = 5_000,

    /** Fuel: one market unit fills 100 ship units (from the refuel endpoint's description). */
    val fuelUnitsPerMarketUnit: Int = 100,
)

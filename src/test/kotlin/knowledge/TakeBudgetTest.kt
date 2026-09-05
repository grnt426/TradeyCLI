package knowledge

import model.market.ActivityLevel
import model.market.MarketTradeGood
import model.market.SupplyLevel
import model.market.TradeGoodType
import model.market.TradeSymbol
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The producer's take rate: shared, proportional to stock, banked for a while, never bang-bang. */
class TakeBudgetTest {
    private val t0 = Instant.parse("2026-09-05T12:00:00Z")
    private fun f47(supply: SupplyLevel, activity: ActivityLevel = ActivityLevel.STRONG) =
        MarketTradeGood(TradeSymbol.FAB_MATS, TradeGoodType.EXPORT, 43, supply, 1700, 800, activity)
    private val rules = MarketAssumptions(activityRateFactor = mapOf(ActivityLevel.RESTRICTED to 0.5, ActivityLevel.WEAK to 1.0, ActivityLevel.GROWING to 1.0, ActivityLevel.STRONG to 1.0))

    @Test
    fun `three haulers share one producer's hourly rate instead of each taking a load`() {
        val budget = TakeBudget()
        val moderate = f47(SupplyLevel.MODERATE)
        assertEquals(107, budget.perHour(moderate, rules).toInt(), "2.5 volumes of 43 an hour at MODERATE")
        val first = budget.take("X1-TH77-F47", moderate, 80, rules, t0)
        val second = budget.take("X1-TH77-F47", moderate, 80, rules, t0)
        val third = budget.take("X1-TH77-F47", moderate, 80, rules, t0)
        assertEquals(107, first + second + third, "the first hour's stock, however many ships arrive: $first $second $third")
        assertEquals(0, budget.available("X1-TH77-F47", moderate, rules, t0))
        // Half an hour later half an hour's production is back.
        assertTrue(budget.available("X1-TH77-F47", moderate, rules, t0.plusSeconds(1800)) in 53..54)
    }

    @Test
    fun `the rate follows the stock level and a lull banks at most a bucket and a half`() {
        val budget = TakeBudget()
        assertEquals(43, budget.perHour(f47(SupplyLevel.LIMITED), rules).toInt(), "LIMITED still trickles: no dead stop")
        assertEquals(43 * 3, budget.perHour(f47(SupplyLevel.LIMITED), MarketAssumptions()).toInt(), "a STRONG producer replaces stock three times as fast")
        assertEquals(0, budget.perHour(f47(SupplyLevel.SCARCE), rules).toInt())
        assertEquals(86, budget.perHour(f47(SupplyLevel.HIGH, ActivityLevel.RESTRICTED), rules).toInt(), "RESTRICTED halves the rate")
        budget.take("M", f47(SupplyLevel.HIGH), 0, rules, t0)
        val later = budget.available("M", f47(SupplyLevel.HIGH), rules, t0.plusSeconds(6 * 3600))
        assertEquals((172 * 1.5).toInt(), later, "six idle hours bank a bucket and a half, not six hours")
        // The level fell while we were away: the bank shrinks to the new level's bucket.
        assertTrue(budget.available("M", f47(SupplyLevel.LIMITED), rules, t0.plusSeconds(6 * 3600)) <= (43 * 1.5).toInt() + 1)
    }

    @Test
    fun `a refund returns what was granted but not bought`() {
        val budget = TakeBudget()
        val listing = f47(SupplyLevel.HIGH)
        val granted = budget.take("M", listing, 100, rules, t0)
        assertEquals(100, granted)
        budget.refund("M", listing, 40, rules, t0)
        assertEquals(112, budget.available("M", listing, rules, t0))
    }
}

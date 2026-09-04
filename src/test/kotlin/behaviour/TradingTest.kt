package behaviour

import behaviour.decisions.Trading
import behaviour.decisions.TradingAssumptions
import engine.Event
import kotlinx.coroutines.test.TestCoroutineScheduler
import model.market.TradeSymbol
import model.market.TransactionType
import plan.Assignment
import plan.Plan
import sim.Fixtures
import sim.SimRun
import sim.SimSeed
import sim.SimUniverse
import sim.VirtualClock
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Trading against the real X1-TH77 prices the probe read on 2026-09-04. */
class TradingTest {

    private val now = Instant.parse("2026-09-04T12:00:00Z")
    private fun pricedSeed(): SimSeed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))

    @Test
    fun `the ranking finds the fabrics run and prices it below the naive margin`() {
        val seed = pricedSeed()
        val universe = SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))
        val snap = SimRun.worldFrom(universe).snapshot(1).let { s ->
            // the seed's markets carry the prices the probe read; make them visible to the ranking
            s.copy(markets = seed.markets.associateBy { it.symbol }.mapValues { (_, m) -> m.also { it.lastRead = now } })
        }
        val ship = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val plans = Trading.rank(snap, ship, now)
        assertTrue(plans.isNotEmpty())
        val fabrics = plans.first { it.good == TradeSymbol.FABRICS && it.source.symbol == "X1-TH77-E45" && it.destination.symbol == "X1-TH77-D41" }
        assertEquals(40, fabrics.units, "a full hold")
        assertTrue(fabrics.profit < 40L * fabrics.marginPerUnit, "impact discounts the naive margin: ${fabrics.profit} vs ${40 * fabrics.marginPerUnit}")
        assertTrue(fabrics.profit > 25_000, fabrics.summary())
        assertTrue(plans.first().creditsPerHour > 50_000, "the best run beats mining by a wide margin: ${plans.first().summary()}")
        assertEquals(plans.sortedByDescending { it.creditsPerHour }, plans)
    }

    @Test
    fun `load sizing stops when the moving prices eat the margin or the money runs out`() {
        val a = TradingAssumptions()
        val cheap = Trading.sizeLoad(buyPrice = 100, buyVolume = 10, sellPrice = 130, sellVolume = 10, capacity = 200, budget = 1_000_000, a)
        assertTrue(cheap.units in 10..190, "margin gone before the hold is full: ${cheap.units}")
        val broke = Trading.sizeLoad(buyPrice = 100, buyVolume = 10, sellPrice = 300, sellVolume = 10, capacity = 100, budget = 1500, a)
        assertEquals(10, broke.units)
        val none = Trading.sizeLoad(buyPrice = 100, buyVolume = 10, sellPrice = 110, sellVolume = 10, capacity = 100, budget = 1_000_000, a)
        assertEquals(0, none.units)
    }

    @Test
    fun `trade earns credits in the simulator, buys within its means and never loses money over a day`() {
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "trade")))
        val report = SimRun(pricedSeed(), plan, hours = 24).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        assertTrue(report.earned > 100_000, "earned ${report.earned}")
        val buys = report.trace.transactions.filter { it.type == TransactionType.PURCHASE && it.tradeSymbol != TradeSymbol.FUEL }
        val sales = report.trace.transactions.filter { it.type == TransactionType.SELL }
        assertTrue(buys.isNotEmpty() && sales.isNotEmpty())
        assertTrue(sales.sumOf { it.totalPrice.toLong() } > buys.sumOf { it.totalPrice.toLong() })
        assertTrue(report.creditsByHour.zipWithNext().count { (a, b) -> b < a - 5_000 } <= 2, "credits by hour: ${report.creditsByHour}")
        val phases = report.trace.phaseNames(Fixtures.COMMAND_SHIP).distinct()
        assertTrue(phases.containsAll(listOf("plan", "travel to source", "buy", "travel to destination", "sell", "refuel")), phases.toString())
        assertTrue(report.callsPerHour < 1000, "${report.callsPerHour} calls/hour against a budget of 7200")
    }

    @Test
    fun `the default plan puts the frigate on trade and the probe on the survey`() {
        val plan = SimRun.defaultPlan(pricedSeed().ships)
        assertEquals("trade", plan.assignmentFor(Fixtures.COMMAND_SHIP)?.behaviour)
        assertEquals("probeMarkets", plan.assignmentFor(Fixtures.PROBE)?.behaviour)
    }

    @Test
    fun `a fleet goal makes the trader buy a shuttle at a yard and the supervisor puts it to work`() {
        val seed = pricedSeed()
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "trade"))).withGoal(plan.FleetGoal(model.ship.ShipType.SHIP_LIGHT_SHUTTLE, 1, reserve = 50_000))
        val report = SimRun(seed, plan, hours = 6).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val purchase = report.trace.events.filterIsInstance<Event.ShipPurchased>()
        assertEquals(1, purchase.size, "exactly one shuttle: ${purchase}")
        val shuttle = purchase.first().ship
        assertTrue(report.trace.phaseNames(shuttle).contains("sell"), "the shuttle traded: ${report.trace.phaseNames(shuttle).distinct()}")
        assertTrue(report.earned > 0)
    }
}

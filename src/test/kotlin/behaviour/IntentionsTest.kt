package behaviour

import behaviour.decisions.CreditPoint
import behaviour.decisions.CreditsTrend
import behaviour.decisions.Intent
import behaviour.decisions.Intentions
import engine.ShipStatus
import engine.Snapshot
import kotlinx.coroutines.test.TestCoroutineScheduler
import model.market.MarketTransaction
import model.market.TradeSymbol
import model.market.TransactionType
import model.ship.ShipType
import plan.Assignment
import plan.FleetGoal
import plan.Goals
import plan.Plan
import sim.Fixtures
import sim.SimRun
import sim.SimSeed
import sim.SimUniverse
import sim.VirtualClock
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreditsTrendTest {
    private val now = Instant.parse("2026-09-04T12:00:00Z")

    private fun rising(perMinute: Long, minutes: Int): List<CreditPoint> =
        (0..minutes).map { CreditPoint(now.minus(Duration.ofMinutes((minutes - it).toLong())), 100_000 + perMinute * it) }

    @Test
    fun `the trend is the slope of the last half hour and the projection continues it`() {
        val history = rising(1_000, 60)
        val trend = CreditsTrend.trend(history, now)
        assertEquals(60_000.0, trend.perHour, 1.0)
        assertEquals(160_000.0, trend.nowValue, 1.0)
        val graph = CreditsTrend.graph(history, now, historyColumns = 36, projectionColumns = 16)
        assertEquals(52, graph.columns.size)
        assertEquals(16, graph.columns.count { it.projected })
        assertEquals(160_000L + 24_000, graph.projectedEnd.toLong(), "24 minutes ahead at 1,000 a minute")
        assertTrue(graph.columns.first().value!! < graph.columns.last().value!!)
        assertEquals(graph.max, graph.columns.last().value)
        assertEquals(graph.eighths(graph.max, 6), 48)
        assertEquals(graph.eighths(graph.min, 6), 0)
    }

    @Test
    fun `one point or none gives a flat trend and quiet buckets carry the last value forward`() {
        assertEquals(0.0, CreditsTrend.trend(emptyList(), now).perHour)
        val single = listOf(CreditPoint(now.minus(Duration.ofMinutes(40)), 500L))
        val graph = CreditsTrend.graph(single, now)
        assertEquals(0.0, graph.trend.perHour)
        assertTrue(graph.columns.filter { !it.projected }.takeLast(5).all { it.value == 500L }, "carried forward")
        assertEquals("1.50M", CreditsTrend.compact(1_500_000))
        assertEquals("693k", CreditsTrend.compact(692_768))
    }
}

class IntentionsTest {
    private val now = Instant.parse("2026-09-04T12:00:00Z")

    private fun snapshot(): Snapshot {
        val seed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))
        val universe = SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))
        return SimRun.worldFrom(universe, seed).snapshot(1)
    }

    @Test
    fun `without a plan it says so, with one it reports each ship's phase and flags stale ones`() {
        val bare = snapshot()
        assertEquals(Intent.Tone.WARN, Intentions.describe(bare, now).single().tone)
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "trade"), Assignment(Fixtures.PROBE, "probeMarkets")))
        val snap = bare.copy(
            plan = plan,
            shipStatus = mapOf(
                Fixtures.COMMAND_SHIP to ShipStatus("trade", "sell", "SHIP_PARTS at X1-TH77-H51", now.minusSeconds(30)),
                Fixtures.PROBE to ShipStatus("probeMarkets", "travel", "to X1-TH77-J57", now.minus(Duration.ofMinutes(25))),
            ),
        )
        val lines = Intentions.describe(snap, now)
        val frigate = lines.first { it.text.startsWith(Fixtures.COMMAND_SHIP) }
        val probe = lines.first { it.text.startsWith(Fixtures.PROBE) }
        assertEquals(Intent.Tone.GOOD, frigate.tone)
        assertTrue(frigate.text.contains("sell SHIP_PARTS"), frigate.text)
        assertEquals(Intent.Tone.WARN, probe.tone)
        assertTrue(probe.text.contains("last seen 25 min ago"), probe.text)
    }

    @Test
    fun `fleet goals become saving, ready or met, with an estimate from the trend`() {
        val base = snapshot()
        val goal = FleetGoal(ShipType.SHIP_LIGHT_SHUTTLE, 2, reserve = 150_000)
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "trade")), Goals(fleet = listOf(goal)))
        val listed = base.shipyards.getValue("X1-TH77-A2").priceOf(ShipType.SHIP_LIGHT_SHUTTLE)!!
        val poor = base.copy(plan = plan, agent = base.agent!!.copy(credits = 100_000))
        val saving = Intentions.describe(poor, now, behaviour.decisions.Trend(120_000.0, 100_000.0, 5)).first { it.text.startsWith("Saving") }
        assertTrue(saving.text.contains("#1 of 2"), saving.text)
        assertTrue(saving.text.contains("${Intentions.format(listed + 150_000)}"), saving.text)
        assertTrue(saving.text.contains("min at the current rate"), saving.text)
        val rich = base.copy(plan = plan, agent = base.agent!!.copy(credits = 700_000))
        val ready = Intentions.describe(rich, now).first { it.text.startsWith("Ready") }
        assertTrue(ready.text.contains("X1-TH77-A2"), ready.text)
        val met = base.copy(plan = Plan(goals = Goals(fleet = listOf(goal.copy(count = 0)))))
        assertTrue(Intentions.describe(met, now).any { it.text.startsWith("Fleet goal met") })
    }

    @Test
    fun `recent transactions become a one-line trading summary`() {
        val base = snapshot()
        val t = now.minus(Duration.ofMinutes(10)).toString()
        val snap = base.copy(
            plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "trade"))),
            recentTransactions = listOf(
                MarketTransaction(Fixtures.COMMAND_SHIP, "X1-TH77-D41", TradeSymbol.SHIP_PARTS, TransactionType.PURCHASE, 40, 3000, 120_000, t),
                MarketTransaction(Fixtures.COMMAND_SHIP, "X1-TH77-H51", TradeSymbol.SHIP_PARTS, TransactionType.SELL, 40, 7000, 280_000, t),
                MarketTransaction(Fixtures.COMMAND_SHIP, "X1-TH77-H51", TradeSymbol.FUEL, TransactionType.PURCHASE, 2, 72, 144, t),
            ),
        )
        val line = Intentions.describe(snap, now).first { it.text.startsWith("Trading") }
        assertTrue(line.text.contains("1 loads sold"), line.text)
        assertTrue(line.text.contains("+159,856"), line.text)
        assertEquals(Intent.Tone.GOOD, line.tone)
    }
}

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
        // Bars stand on the bottom row: the max fills every row, the min fills none, a middle value fills the lower rows only.
        assertTrue((0 until 6).all { graph.glyphIndex(graph.max, it, 6) == 8 })
        assertTrue((0 until 6).all { graph.glyphIndex(graph.min, it, 6) == 0 })
        val mid = (graph.min + graph.max) / 2
        assertEquals(0, graph.glyphIndex(mid, 0, 6), "top row empty for a middle value")
        assertEquals(8, graph.glyphIndex(mid, 5, 6), "bottom row full for a middle value")
        assertEquals(52, graph.axis().length)
        assertEquals('|', graph.axis()[36], "the projection starts after 36 history columns")
        assertTrue(graph.axisLabels().startsWith("-54m") && graph.axisLabels().endsWith("+24m") && graph.axisLabels().contains("now"), graph.axisLabels())
        assertEquals(graph.columns.last().value, graph.projectedEnd.toLong())
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
        val bareLines = Intentions.describe(bare, now)
        assertTrue(bareLines.first().text.startsWith("Nobody is running the plan"), bareLines.first().text)
        assertTrue(bareLines.any { it.text.startsWith("No plan") && it.tone == Intent.Tone.WARN }, bareLines.toString())
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
        // The bank must also keep working capital for every trader, so the reserve shown is the larger of the two.
        assertTrue(saving.text.contains("${Intentions.format(listed + knowledge.Strategy.purchaseReserve(150_000, poor))}"), saving.text)
        assertTrue(saving.text.contains("at the current rate"), saving.text)
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

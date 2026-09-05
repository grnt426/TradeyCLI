package behaviour

import behaviour.decisions.CreditsTrend
import behaviour.decisions.Summary
import engine.Event
import knowledge.Strategy
import model.market.MarketTransaction
import model.market.TradeSymbol
import model.market.TransactionType
import plan.Assignment
import plan.Plan
import sim.Fixtures
import sim.SimRun
import sim.SimSeed
import storage.LedgerEntry
import storage.TaggedTransaction
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The summary screen's numbers: purposes and sources from tags, health per system, the gate rush rule. */
class SummaryTest {
    private val now = Instant.parse("2026-09-05T12:00:00Z")
    private fun pricedSeed(): SimSeed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))

    private fun tx(type: TransactionType, good: TradeSymbol, total: Int, tag: String?) =
        TaggedTransaction(MarketTransaction("TRIPLEHAT-1", "X1-TH77-A1", good, type, 1, total, total, now.toString()), tag)

    @Test
    fun `spending and revenue land in the categories their tags say, fuel included`() {
        val txs = listOf(
            tx(TransactionType.PURCHASE, TradeSymbol.FAB_MATS, 60_000, "gate:X1-TH77-I54"),
            tx(TransactionType.PURCHASE, TradeSymbol.FUEL, 500, "gate:X1-TH77-I54"),
            tx(TransactionType.PURCHASE, TradeSymbol.QUARTZ_SAND, 2_000, "nurse:X1-TH77-I54"),
            tx(TransactionType.SELL, TradeSymbol.QUARTZ_SAND, 2_200, "nurse:X1-TH77-I54"),
            tx(TransactionType.PURCHASE, TradeSymbol.COPPER, 10_000, "microchips"),
            tx(TransactionType.PURCHASE, TradeSymbol.CLOTHING, 100_000, "trade"),
            tx(TransactionType.PURCHASE, TradeSymbol.FUEL, 300, "trade"),
            tx(TransactionType.SELL, TradeSymbol.CLOTHING, 150_000, "trade"),
            tx(TransactionType.PURCHASE, TradeSymbol.FUEL, 100, "mineAndSell"),
            tx(TransactionType.SELL, TradeSymbol.IRON_ORE, 4_000, "mineAndSell"),
            tx(TransactionType.SELL, TradeSymbol.GOLD, 900, null),
        )
        val ledger = listOf(
            LedgerEntry(now, "TRIPLEHAT-2", "ships", -82_000, "SHIP_LIGHT_SHUTTLE"),
            LedgerEntry(now, "TRIPLEHAT-2", "chart", 5_000, "X1-B31-A1"),
            LedgerEntry(now, "", "contract", 30_000, "fulfilled abc123"),
        )
        val snap = SimRun.worldFrom(sim.SimUniverse(pricedSeed(), sim.VirtualClock(kotlinx.coroutines.test.TestCoroutineScheduler(), now))).snapshot(1)
            .copy(taggedTransactions = txs, ledger = ledger)
        val spending = Summary.spending(snap).associate { it.category to it.credits }
        assertEquals(60_500L, spending[Summary.GATE], "materials plus the hauler's fuel")
        assertEquals(12_000L, spending[Summary.HEALTH], "nursing and chain feeding")
        assertEquals(100_300L, spending[Summary.TRADING])
        assertEquals(100L, spending[Summary.MINING], "mining's only cost is fuel")
        assertEquals(82_000L, spending[Summary.SHIPS])
        val revenue = Summary.revenue(snap).associate { it.category to it.credits }
        assertEquals(150_000L, revenue["arbitrage"])
        assertEquals(4_000L, revenue[Summary.MINING])
        assertEquals(2_200L, revenue[Summary.HEALTH])
        assertEquals(5_000L, revenue[Summary.CHARTING])
        assertEquals(30_000L, revenue[Summary.CONTRACTS])
        assertEquals(900L, revenue[Summary.OTHER])
        assertEquals(Summary.revenue(snap).sortedByDescending { it.credits }, Summary.revenue(snap))
    }

    @Test
    fun `market health per system averages the listings and counts what is wrong`() {
        val snap = SimRun.worldFrom(sim.SimUniverse(pricedSeed(), sim.VirtualClock(kotlinx.coroutines.test.TestCoroutineScheduler(), now))).snapshot(1)
            .let { s -> s.copy(markets = pricedSeed().markets.associateBy { it.symbol }.mapValues { (_, m) -> m.also { it.lastRead = now } }) }
        val health = Summary.marketHealth(snap, now)
        assertEquals(listOf("X1-TH77"), health.map { it.system })
        val h = health.single()
        assertTrue(h.markets > 20 && h.listings > 100, "$h")
        assertTrue(h.score in 0.3..0.9, "a lived-in system sits in the middle: ${h.score}")
        assertTrue(h.restrictedExports >= 0 && h.scarce >= 0)
        assertEquals(0.0, h.oldestReadHours)
    }

    @Test
    fun `the gate rush starts once the bank covers the bill with room to spare, and the hauler joins the traders after`() {
        assertTrue(!Strategy.gateRush(bank = 2_000_000, remainingCost = 2_500_000))
        assertTrue(!Strategy.gateRush(bank = 3_700_000, remainingCost = 2_500_000), "1.5x plus the post-gate reserve is 4.25M")
        assertTrue(Strategy.gateRush(bank = 4_300_000, remainingCost = 2_500_000))
        assertTrue(!Strategy.gateRush(bank = 4_300_000, remainingCost = 0), "nothing left to rush")
        val ship = pricedSeed().ships.first { it.symbol == Fixtures.COMMAND_SHIP }
        val snap = SimRun.worldFrom(sim.SimUniverse(pricedSeed(), sim.VirtualClock(kotlinx.coroutines.test.TestCoroutineScheduler(), now))).snapshot(1)
        assertEquals("trade", Strategy.afterFinished(plan.Phase.BOOM, ship, "supplyGate", snap)?.behaviour)
    }

    @Test
    fun `the gate hauler tags its transactions and the escape progress reads the bill`() {
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "supplyGate", mapOf("site" to "X1-TH77-I54", "reserve" to "50000"))))
        val report = SimRun(pricedSeed(), plan, hours = 3).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val tags = report.trace.tagged.map { it.second }.toSet()
        assertTrue("gate:X1-TH77-I54" in tags, tags.toString())
        assertTrue(report.trace.events.filterIsInstance<Event.Supplied>().isNotEmpty())
    }

    @Test
    fun `progress in escape names the site and the bill`() {
        val seed = pricedSeed()
        val snap = SimRun.worldFrom(sim.SimUniverse(seed, sim.VirtualClock(kotlinx.coroutines.test.TestCoroutineScheduler(), now))).snapshot(1)
            .copy(
                plan = Plan(),
                markets = seed.markets.associateBy { it.symbol }.mapValues { (_, m) -> m.also { it.lastRead = now } },
                constructionBill = listOf(model.ConstructionMaterial(TradeSymbol.FAB_MATS, 1600, 634), model.ConstructionMaterial(TradeSymbol.ADVANCED_CIRCUITRY, 400, 151)),
            )
        val progress = Summary.progress(snap, now, CreditsTrend.trend(emptyList(), now))
        assertTrue(progress.headline.startsWith("ESCAPE: gate X1-TH77-I54"), progress.headline)
        assertTrue(progress.headline.contains("FAB_MATS 634/1600"), progress.headline)
        assertTrue(progress.lines.any { it.text.startsWith("To finish") }, progress.lines.toString())
    }
}

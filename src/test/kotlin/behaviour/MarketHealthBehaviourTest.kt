package behaviour

import behaviour.decisions.Trading
import engine.Event
import knowledge.MarketHealth
import kotlinx.coroutines.test.TestCoroutineScheduler
import model.market.ActivityLevel
import model.market.SupplyLevel
import model.market.TradeGoodType
import model.market.TradeSymbol
import model.market.TransactionType
import plan.Assignment
import plan.Chain
import plan.Leg
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

/** The market rules at work in the behaviours, against the real X1-TH77 prices of 2026-09-04. */
class MarketHealthBehaviourTest {
    private val now = Instant.parse("2026-09-04T12:00:00Z")
    private fun pricedSeed(): SimSeed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))

    /** The seed with one listing's supply and activity replaced. */
    private fun SimSeed.withListing(market: String, good: TradeSymbol, supply: SupplyLevel, activity: ActivityLevel? = null): SimSeed = copy(
        markets = markets.map { m ->
            if (m.symbol != market) m
            else m.copy(tradeGoods = m.tradeGoods.map { g -> if (g.symbol == good) g.copy(supply = supply, activity = activity ?: g.activity) else g }).also { it.lastRead = m.lastRead }
        },
    )

    @Test
    fun `the ranking drops starved sources and saturated destinations and sorts by health-weighted score`() {
        val seed = pricedSeed()
            .withListing("X1-TH77-E45", TradeSymbol.FABRICS, SupplyLevel.SCARCE)
            .withListing("X1-TH77-D41", TradeSymbol.EQUIPMENT, SupplyLevel.ABUNDANT)
        val universe = SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))
        val snap = SimRun.worldFrom(universe).snapshot(1).let { s ->
            s.copy(markets = seed.markets.associateBy { it.symbol }.mapValues { (_, m) -> m.also { it.lastRead = now } })
        }
        val plans = Trading.rank(snap, snap.ships.getValue(Fixtures.COMMAND_SHIP), now)
        assertTrue(plans.isNotEmpty())
        assertTrue(plans.none { it.good == TradeSymbol.FABRICS && it.source.symbol == "X1-TH77-E45" }, "a SCARCE export is not a source")
        assertTrue(plans.none { it.good == TradeSymbol.EQUIPMENT && it.destination.symbol == "X1-TH77-D41" }, "an ABUNDANT import is not a destination")
        assertEquals(plans.sortedByDescending { it.score }, plans)
        assertTrue(plans.all { it.health.isNotBlank() })
        val exportToImport = plans.filter { it.source.typeOf(it.good) == TradeGoodType.EXPORT && it.destination.typeOf(it.good) == TradeGoodType.IMPORT }
        assertTrue(exportToImport.isNotEmpty())
        assertTrue(exportToImport.all { it.score <= it.creditsPerHour + 1e-6 })
        plans.filter { it.source.typeOf(it.good) == TradeGoodType.IMPORT }.forEach { p ->
            assertTrue(p.score < p.creditsPerHour * 0.5, "buying from an import market is penalised: ${p.summary()} score ${p.score}")
        }
    }

    @Test
    fun `the gate hauler nurses a short producer instead of buying through it, and buys blindly with nurse off`() {
        // F47's FAB_MATS stock is LIMITED: the hauler may take none, so it feeds F47's inputs (iron from the refinery H50).
        val seed = pricedSeed().withListing("X1-TH77-F47", TradeSymbol.FAB_MATS, SupplyLevel.LIMITED)
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "supplyGate", mapOf("site" to "X1-TH77-I54", "reserve" to "50000", "only" to "FAB_MATS"))))
        val report = SimRun(seed, plan, hours = 3).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val fed = report.trace.events.filterIsInstance<Event.Sold>().filter { it.waypoint == "X1-TH77-F47" }
        assertTrue(fed.isNotEmpty(), "inputs were delivered to F47: ${report.trace.phaseNames(Fixtures.COMMAND_SHIP).distinct()}")
        assertTrue(fed.all { it.good in setOf("IRON", "QUARTZ_SAND") }, fed.map { it.good }.distinct().toString())
        assertTrue(report.trace.tagged.none { it.first.tradeSymbol == TradeSymbol.FAB_MATS && it.first.type == TransactionType.PURCHASE }, "no FAB_MATS bought while the producer is short")
        assertTrue(report.trace.tagged.any { it.second == "nurse:X1-TH77-I54" && it.first.tradeSymbol == TradeSymbol.IRON }, "nursing is tagged for the site so the summary files it under market health")

        val blind = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "supplyGate", mapOf("site" to "X1-TH77-I54", "reserve" to "50000", "only" to "FAB_MATS", "nurse" to "off"))))
        val blindReport = SimRun(seed, blind, hours = 3).run()
        assertTrue(blindReport.trace.events.filterIsInstance<Event.Supplied>().any { it.good == "FAB_MATS" }, "nurse off buys regardless")
    }

    @Test
    fun `a chain bee leaves a fed consumer alone and a starved producer untouched`() {
        // HIGH is fed (MarketAssumptions.feedUntil): a bee that keeps delivering buries the import to ABUNDANT and sells at a third of cost.
        val seed = pricedSeed()
            .withListing("X1-TH77-A3", TradeSymbol.COPPER, SupplyLevel.HIGH)
            .withListing("X1-TH77-H50", TradeSymbol.IRON, SupplyLevel.SCARCE)
        val chain = Chain("test", legs = listOf(Leg(TradeSymbol.COPPER, "X1-TH77-H50", "X1-TH77-A3"), Leg(TradeSymbol.IRON, "X1-TH77-H50", "X1-TH77-F47")), ships = listOf(Fixtures.COMMAND_SHIP), enrolledAt = now.toString(), reserve = 50_000)
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "feed", mapOf("chain" to "test"))), chains = listOf(chain))
        val report = SimRun(seed, plan, hours = 2).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val bought = report.trace.events.filterIsInstance<Event.Bought>()
        assertTrue(bought.none { it.good == "COPPER" }, "no copper bought for a consumer already HIGH in it: $bought")
        assertTrue(bought.none { it.good == "IRON" }, "no iron taken from a producer that is SCARCE in it: $bought")
        val listing = seed.markets.first { it.symbol == "X1-TH77-A3" }.good(TradeSymbol.COPPER)!!
        assertTrue(listing.supply >= knowledge.MarketAssumptions().feedUntil)
    }
}

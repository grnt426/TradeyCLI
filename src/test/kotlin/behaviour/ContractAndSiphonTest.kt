package behaviour

import behaviour.decisions.Mining
import engine.Event
import kotlinx.coroutines.test.TestCoroutineScheduler
import model.market.TradeSymbol
import plan.Assignment
import plan.Plan
import sim.Fixtures
import sim.SimRun
import sim.SimSeed
import sim.SimUniverse
import sim.VirtualClock
import storage.ExtractionRecord
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractAndSiphonTest {
    private val now = Instant.parse("2026-09-04T12:00:00Z")
    private fun pricedSeed(): SimSeed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))

    @Test
    fun `runContract negotiates, procures, delivers and fulfils contracts in a row and records each`() {
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "runContract")))
        val report = SimRun(pricedSeed(), plan, hours = 6).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val fulfilled = report.trace.events.filterIsInstance<Event.ContractFulfilled>()
        assertTrue(fulfilled.size >= 2, "several contracts in six hours: ${fulfilled.size}")
        assertTrue(report.trace.events.any { it is Event.Delivered })
        assertTrue(report.trace.contracts.values.count { it.fulfilled } == fulfilled.size)
        val phases = report.trace.phaseNames(Fixtures.COMMAND_SHIP).distinct()
        assertTrue(phases.containsAll(listOf("find contract", "accept", "procure", "deliver", "fulfil")), phases.toString())
        assertTrue(report.earned > 0, "contracts pay: ${report.earned}")
    }

    @Test
    fun `the frigate can siphon the gas giant and sell at the station beside it`() {
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "mineAndSell", mapOf("asteroid" to "X1-TH77-C38", "market" to "X1-TH77-C39"))))
        val report = SimRun(pricedSeed(), plan, hours = 2).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val gases = report.trace.extractions.map { it.good }.toSet()
        assertTrue(gases.isNotEmpty() && gases.all { it in setOf(TradeSymbol.HYDROCARBON, TradeSymbol.LIQUID_HYDROGEN, TradeSymbol.LIQUID_NITROGEN) }, gases.toString())
        assertTrue(report.unitsSold > 0)
    }

    @Test
    fun `observed yields replace the strength guess once a rock has three extractions, and the notes say so`() {
        val seed = pricedSeed()
        val universe = SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))
        val base = SimRun.worldFrom(universe, seed).snapshot(1)
        val ship = base.ships.getValue(Fixtures.COMMAND_SHIP)
        val guess = Mining.rank(base, ship, now).first { it.asteroid.symbol == Fixtures.METAL_ASTEROID && it.market.symbol == Fixtures.NEAR_MARKET }
        val records = (0 until 6).map { i -> ExtractionRecord(ship.symbol, Fixtures.METAL_ASTEROID, TradeSymbol.IRON_ORE, 25, null, if (i == 5) listOf("UNSTABLE") else emptyList(), now.minus(Duration.ofMinutes((60 - i * 10).toLong()))) }
        val observed = Mining.observe(records, now).getValue(Fixtures.METAL_ASTEROID)
        assertEquals(6, observed.extractions)
        assertEquals(25.0, observed.unitsPerExtraction)
        assertEquals(6.0, observed.perHour!!, 0.01)
        assertEquals(listOf("UNSTABLE"), observed.modifiersSeen)
        val seen = Mining.rank(base.copy(extractions = records), ship, now).first { it.asteroid.symbol == Fixtures.METAL_ASTEROID && it.market.symbol == Fixtures.NEAR_MARKET }
        assertTrue(seen.cycleSeconds < guess.cycleSeconds, "25 a pull fills the hold faster than the guess of 10")
        assertTrue(seen.riskNotes.any { it.startsWith("observed 6 ext") }, seen.riskNotes.toString())
    }
}

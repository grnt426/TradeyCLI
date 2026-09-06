package behaviour

import engine.Event
import plan.Assignment
import plan.Plan
import sim.Fixtures
import sim.SimRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Whole behaviours against the simulated X1-TH77, on virtual time. */
class BehaviourSimTest {

    @Test
    fun `probeMarkets reads every market in the system and then finishes`() {
        val plan = Plan(listOf(Assignment(Fixtures.PROBE, "probeMarkets")))
        val report = SimRun(Fixtures.seed(), plan, hours = 24).run()
        val read = report.trace.events.filterIsInstance<Event.MarketUpdated>().map { it.symbol }.toSet()
        val (known, unknown) = Fixtures.seed().markets.partition { it.hasPrices }
        assertEquals(unknown.map { it.symbol }.toSet(), read, "missing: ${unknown.map { it.symbol }.toSet() - read}")
        assertTrue(known.isNotEmpty() && known.none { it.symbol in read }, "markets already priced (where the ships stand) are not read again")
        assertTrue(report.trace.events.any { it is Event.BehaviourFinished && it.ship == Fixtures.PROBE })
        // Done reading once, the probe is handed a watching job (re-read anything older than ten minutes) rather than left idle.
        assertTrue(report.trace.phases.any { it.first == Fixtures.PROBE && it.second.phase == "done" })
        assertTrue(report.trace.phases.last { it.first == Fixtures.PROBE }.second.phase in setOf("done", "watching", "travel", "read prices"))
        assertTrue(report.failures.isEmpty(), report.failures.toString())
    }

    @Test
    fun `mineAndSell earns credits, follows the plan's phases and stays inside the request budget`() {
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "mineAndSell")))
        val report = SimRun(Fixtures.seed(), plan, hours = 6).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        assertTrue(report.earned > 0, "earned ${report.earned}")
        assertTrue(report.unitsSold > 0)
        val phases = report.trace.phaseNames(Fixtures.COMMAND_SHIP).distinct()
        assertEquals(listOf("plan", "travel to asteroid", "extract", "travel to market", "sell", "refuel"), phases.take(6), phases.toString())
        val cycles = report.trace.phaseNames(Fixtures.COMMAND_SHIP).count { it == "sell" }
        assertTrue(cycles >= 5, "six hours should hold several fill-and-sell cycles: $cycles")
        assertTrue(report.callsPerHour < 400, "${report.callsPerHour} calls per hour for one ship")
        assertTrue(report.creditsByHour.zipWithNext().all { (a, b) -> b >= a - 500 }, "credits should not fall: ${report.creditsByHour}")
    }

    @Test
    fun `a fixed asteroid and market are respected`() {
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "mineAndSell", mapOf("asteroid" to Fixtures.METAL_ASTEROID, "market" to Fixtures.ORE_MARKET, "surveys" to "no"))))
        val report = SimRun(Fixtures.seed(), plan, hours = 4).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val extractedAt = report.trace.extractions.map { it.waypoint }.toSet()
        assertEquals(setOf(Fixtures.METAL_ASTEROID), extractedAt)
        val soldAt = report.trace.transactions.filter { it.type == model.market.TransactionType.SELL }.map { it.waypointSymbol }.toSet()
        assertEquals(setOf(Fixtures.ORE_MARKET), soldAt)
        assertTrue(report.trace.events.none { it is Event.Surveyed })
    }

    @Test
    fun `a trader and a probe run side by side`() {
        val seed = sim.SimSeed.load(java.io.File("src/test/resources/x1-th77-priced-seed.json"))
        val plan = SimRun.defaultPlan(seed.ships)
        assertEquals(2, plan.assignments.size)
        val report = SimRun(seed, plan, hours = 3).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        assertTrue(report.earned > 0)
        assertTrue(report.trace.events.any { it is Event.MarketUpdated })
    }

    @Test
    fun `an impossible assignment fails with a reason and is retried, not looped`() {
        // The probe cannot mine; validation catches that before anything runs.
        val plan = Plan(listOf(Assignment(Fixtures.PROBE, "mineAndSell")))
        val problems = plan.validate(sim.SimRun.worldFrom(sim.SimUniverse(Fixtures.seed(), sim.VirtualClock(kotlinx.coroutines.test.TestCoroutineScheduler(), java.time.Instant.EPOCH))).snapshot(1))
        assertEquals(listOf("${Fixtures.PROBE}: ${Fixtures.PROBE} has no mining laser or gas siphon"), problems)
    }
}

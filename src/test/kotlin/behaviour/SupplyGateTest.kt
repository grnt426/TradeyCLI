package behaviour

import engine.Event
import model.market.TradeSymbol
import plan.Assignment
import plan.Plan
import sim.Fixtures
import sim.SimRun
import sim.SimSeed
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupplyGateTest {
    private fun pricedSeed(): SimSeed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))

    @Test
    fun `the frigate buys gate materials, routes through a fuel stop, and supplies the site load after load`() {
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "supplyGate", mapOf("site" to "X1-TH77-I54", "reserve" to "50000"))))
        val report = SimRun(pricedSeed(), plan, hours = 4).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val supplied = report.trace.events.filterIsInstance<Event.Supplied>()
        assertTrue(supplied.size >= 3, "several loads in four hours: ${supplied.size}; phases ${report.trace.phases.filter { it.first == Fixtures.COMMAND_SHIP }.map { it.second.phase + ": " + it.second.detail }}")
        assertTrue(supplied.all { it.site == "X1-TH77-I54" })
        assertTrue(supplied.map { it.good }.toSet().isNotEmpty())
        assertEquals(supplied.sumOf { it.units }, report.trace.supplies.sumOf { it.units })
        // The gate is 527 from D42 and 470 from F47 against a 400 tank: every haul must have refuelled on the way.
        val phases = report.trace.phaseNames(Fixtures.COMMAND_SHIP)
        assertTrue(phases.contains("haul") && phases.contains("supply") && phases.contains("buy"), phases.distinct().toString())
        val refuels = report.trace.events.filterIsInstance<Event.Refueled>()
        assertTrue(refuels.any { it.waypoint !in setOf("X1-TH77-I54", "X1-TH77-D42", "X1-TH77-F47") }, "a fuel stop en route: ${refuels.map { it.waypoint }.distinct()}")
        // Purchases are tagged with the site so the gate ledger can total them.
        assertTrue(report.trace.tagged.any { it.second == "gate:X1-TH77-I54" && it.first.tradeSymbol in setOf(TradeSymbol.FAB_MATS, TradeSymbol.ADVANCED_CIRCUITRY) })
    }
}

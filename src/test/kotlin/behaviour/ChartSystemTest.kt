package behaviour

import engine.Event
import model.WaypointTrait
import model.WaypointTraitSymbol
import plan.Assignment
import plan.Plan
import sim.Fixtures
import sim.SimRun
import sim.SimSeed
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChartSystemTest {
    @Test
    fun `the probe charts every uncharted waypoint nearest first, is paid, and stops`() {
        val seed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))
        val uncharted = setOf("X1-TH77-H52", "X1-TH77-G49", "X1-TH77-B9")
        val edited = seed.copy(waypoints = seed.waypoints.map { w -> if (w.symbol in uncharted) w.copy(traits = listOf(WaypointTrait(WaypointTraitSymbol.UNCHARTED, "Uncharted", ""))) else w })
        val plan = Plan(listOf(Assignment(Fixtures.PROBE, "chartSystem")))
        val report = SimRun(edited, plan, hours = 3).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val charted = report.trace.events.filterIsInstance<Event.Charted>()
        assertEquals(uncharted, charted.map { it.waypoint }.toSet())
        assertEquals(3 * 5_000L, report.earned)
        assertTrue(report.trace.events.any { it is Event.BehaviourFinished && it.ship == Fixtures.PROBE })
    }
}

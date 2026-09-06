package behaviour

import engine.Event
import plan.Assignment
import plan.Plan
import sim.Fixtures
import sim.SimRun
import sim.SimSeed
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** The surveyor's rounds in the simulator, on the frigate (the only seed ship with the mount). */
class SurveyTest {
    @Test
    fun `the surveyor covers the rocks, rests when covered, and miners get its surveys`() {
        val seed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "survey", mapOf("every" to "60"))))
        val report = SimRun(seed, plan, hours = 3).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val surveyed = report.trace.events.filterIsInstance<Event.Surveyed>()
        assertTrue(surveyed.size >= 4, "several rocks surveyed in three hours: ${surveyed.size}")
        assertTrue(surveyed.map { it.waypoint }.toSet().size >= 3, "different rocks, not the same one over and over: ${surveyed.map { it.waypoint }.distinct()}")
        val phases = report.trace.phaseNames(Fixtures.COMMAND_SHIP)
        assertTrue("read stability" in phases, phases.distinct().toString())
        assertTrue(report.calls < 400, "the rounds are cheap in requests: ${report.calls}")
    }

    @Test
    fun `a ship without the mount is refused by validation`() {
        val seed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))
        val plan = Plan(listOf(Assignment(Fixtures.PROBE, "survey")))
        val snap = SimRun.worldFrom(sim.SimUniverse(seed, sim.VirtualClock(kotlinx.coroutines.test.TestCoroutineScheduler(), java.time.Instant.parse("2026-09-06T12:00:00Z")))).snapshot(1)
        assertTrue(plan.validate(snap).any { it.contains("no surveyor mount") }, plan.validate(snap).toString())
    }
}

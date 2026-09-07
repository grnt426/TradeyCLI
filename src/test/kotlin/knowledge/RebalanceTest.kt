package knowledge

import kotlinx.coroutines.test.TestCoroutineScheduler
import plan.Assignment
import plan.Phase
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

/** The phase change re-plans the ships that already exist. */
class RebalanceTest {
    private val now = Instant.parse("2026-09-05T12:00:00Z")

    @Test
    fun `at boom the probe keeps reading home, extra probes explore, and haulers spread over the neighbours`() {
        val seed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))
        val snap = SimRun.worldFrom(SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))).snapshot(1)
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "supplyGate", mapOf("site" to "X1-TH77-I54")), Assignment(Fixtures.PROBE, "probeMarkets", mapOf("markets" to "X1-TH77-F47", "maxAge" to "5"))))
        val next = Strategy.rebalance(Phase.BOOM, plan, snap, listOf("X1-MF53", "X1-XX21"))
        assertEquals("expand", next.assignmentFor(Fixtures.PROBE)?.behaviour, "the home probe buys the boom fleet, reading prices while it waits")
        assertEquals(emptyMap<String, String>(), next.assignmentFor(Fixtures.PROBE)?.params, "the first probe roams home")
        // The frigate has a real hold: in the boom it trades like a hauler.
        assertEquals("trade", next.assignmentFor(Fixtures.COMMAND_SHIP)?.behaviour)
        assertEquals(plan, Strategy.rebalance(Phase.ESCAPE, plan, snap, emptyList()), "only boom re-plans")
    }
}

package knowledge

import kotlinx.coroutines.test.TestCoroutineScheduler
import model.WaypointTrait
import model.WaypointTraitSymbol
import model.ship.ShipType
import plan.Assignment
import plan.FleetGoal
import plan.FrontierGate
import plan.Phase
import plan.Plan
import plan.Stage
import plan.SystemRecord
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

/** The boom's bookkeeping: goals per system, the frontier, stage transitions. */
class BoomTest {
    private val now = Instant.parse("2026-09-06T15:00:00Z")
    private fun snap(): engine.Snapshot {
        val seed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))
        val s = SimRun.worldFrom(SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))).snapshot(1)
        // the seed's prices are visible to the ranking, as the probe would have read them
        return s.copy(markets = seed.markets.associateBy { it.symbol }.mapValues { (_, m) -> m.also { it.lastRead = now } })
    }

    @Test
    fun `a goal for a system counts only the ships there and is bought only at its yard unless it has none`() {
        val s = snap()
        val home = FleetGoal(ShipType.SHIP_PROBE, 2, system = "X1-TH77")
        val elsewhere = FleetGoal(ShipType.SHIP_PROBE, 2, system = "X1-XX21")
        assertEquals(1, home.owned(s.ships.values))
        assertEquals(0, elsewhere.owned(s.ships.values))
        val plan = Plan().withSystem(SystemRecord("X1-XX21", Stage.RUSH, yard = null))
        with(behaviour.BehaviourScope.Companion) {
            assertTrue(home.buyableAt("X1-TH77", plan))
            assertTrue(!home.buyableAt("X1-XX21", plan))
            assertTrue(elsewhere.buyableAt("X1-TH77", plan), "a system with no yard is seeded from any yard")
            assertTrue(!elsewhere.buyableAt("X1-TH77", plan.withSystem(SystemRecord("X1-XX21", Stage.RUSH, yard = "X1-XX21-B21B"))), "but not once it has a yard of its own")
        }
        assertEquals(2, Plan().withGoal(home).withGoal(elsewhere).goals.fleet.size, "goals for different systems coexist")
    }

    @Test
    fun `at boom home settles, the far gates become the frontier, two probes pioneer and the rest watch`() {
        val s = snap()
        val plan = Plan(listOf(Assignment(Fixtures.PROBE, "probeMarkets")))
        val next = Strategy.rebalance(Phase.BOOM, plan, s, listOf("X1-MF53-I57", "X1-XX21-Z25C"))
        assertEquals(Stage.SETTLE, next.system("X1-TH77")?.stage)
        assertEquals(listOf(FrontierGate("X1-MF53-I57", "X1-TH77"), FrontierGate("X1-XX21-Z25C", "X1-TH77")), next.frontier)
        assertEquals("probeMarkets", next.assignmentFor(Fixtures.PROBE)?.behaviour, "the only probe keeps watching home")
        assertEquals("trade", next.assignmentFor(Fixtures.COMMAND_SHIP)?.behaviour)
        assertTrue(next.withFrontier(listOf(FrontierGate("X1-TH77-I54", "X1-XX21"))).frontier.none { it.system == "X1-TH77" }, "a known system never re-enters the frontier")
    }

    @Test
    fun `a rushed system settles once charted and read, or networks when its gate is unbuilt, and a hauler there is sent to it`() {
        val s = snap()
        // Home's seed is charted and read: a RUSH record settles because the gate is under construction in the seed? then it networks.
        val gateUnbuilt = s.waypointsIn("X1-TH77").firstOrNull { it.type == model.system.WaypointType.JUMP_GATE }?.isUnderConstruction == true
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "trade"))).withSystem(SystemRecord("X1-TH77", Stage.RUSH, gate = "X1-TH77-I54"))
        val next = Strategy.advanceSystems(plan, s.copy(plan = plan, markets = s.markets), now)
        val stage = next.system("X1-TH77")?.stage
        assertTrue(stage == (if (gateUnbuilt) Stage.NETWORK else Stage.SETTLE), "stage $stage, gate unbuilt $gateUnbuilt")
        if (gateUnbuilt) {
            val again = Strategy.advanceSystems(next, s.copy(plan = next), now)
            assertEquals("supplyGate", again.assignmentFor(Fixtures.COMMAND_SHIP)?.behaviour, "the trading hauler goes to the gate")
            assertEquals("0.75", again.assignmentFor(Fixtures.COMMAND_SHIP)?.params?.get("reserveShare"))
        }
        val uncharted = s.copy(waypoints = s.waypoints.mapValues { (_, w) -> if (w.symbol == "X1-TH77-B9") w.copy(traits = w.traits + WaypointTrait(WaypointTraitSymbol.UNCHARTED, "Uncharted", "")) else w })
        assertEquals(Stage.RUSH, Strategy.advanceSystems(plan, uncharted.copy(plan = plan), now).system("X1-TH77")?.stage, "still charting: still rushing")
    }
}

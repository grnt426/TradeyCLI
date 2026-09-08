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
        assertEquals("expand", next.assignmentFor(Fixtures.PROBE)?.behaviour, "the only probe buys the boom's ships at home, reading prices while it waits")
        assertEquals("trade", next.assignmentFor(Fixtures.COMMAND_SHIP)?.behaviour)
        assertTrue(next.withFrontier(listOf(FrontierGate("X1-TH77-I54", "X1-XX21"))).frontier.none { it.system == "X1-TH77" }, "a known system never re-enters the frontier")
    }

    @Test
    fun `a system's goal counts the ships bound for it, and spare chart probes move to the system with the most charts left`() {
        val snap = snap()
        val probe = snap.ships.getValue(Fixtures.PROBE)
        val probes = (1..6).map { i -> probe.copy(symbol = "P-$i") }
        val fleet = snap.copy(ships = snap.ships + probes.associateBy { it.symbol })
        val goal = FleetGoal(ShipType.SHIP_PROBE, 2, system = "X1-MF53")
        val bound = Plan(listOf(Assignment("P-1", "chartSystem", mapOf("system" to "X1-MF53")), Assignment("P-2", "chartSystem", mapOf("system" to "X1-MF53"))))
        assertEquals(0, goal.owned(fleet.ships.values), "nobody is there yet")
        assertEquals(2, goal.owned(fleet.ships.values, bound), "two are on the way: the kit is bought, not bought again")
        // Home is charted in the fixture; MF53 is not (its waypoints are unknown, so nothing counts as uncharted) and TH77 has none left.
        val uncharted = fleet.copy(waypoints = fleet.waypoints.mapValues { (_, w) -> if (w.symbol == "X1-TH77-B9") w.copy(traits = w.traits + model.WaypointTrait(WaypointTraitSymbol.UNCHARTED, "Uncharted", "")) else w })
        val crowded = Plan(
            assignments = probes.map { Assignment(it.symbol, "chartSystem", mapOf("system" to "X1-MF53")) },
            phase = Phase.BOOM,
            systems = listOf(SystemRecord("X1-TH77", Stage.SETTLE, gateBuilt = true), SystemRecord("X1-MF53", Stage.RUSH, gateBuilt = true)).associateBy { it.symbol },
        )
        val once = Strategy.spreadProbes(crowded, uncharted)
        val moved = once.assignments.filter { it.params["system"] == "X1-TH77" }
        assertEquals(listOf("P-5", "P-6"), moved.map { it.ship }.sorted(), "the fifth and sixth probes on MF53 are spare and go where charts remain: ${once.assignments.map { it.params }}")
        assertEquals(4, once.assignments.count { it.params["system"] == "X1-MF53" }, "four stay charting MF53")
        assertEquals(once, Strategy.spreadProbes(once, uncharted), "settled")
    }

    @Test
    fun `the boom wants freighters and explorers once, a warp ship charts gate-less systems, and a freighter trades where traders are thinnest`() {
        val snap = snap()
        val plan = Plan(phase = Phase.BOOM, systems = listOf(
            SystemRecord("X1-TH77", Stage.SETTLE, gateBuilt = true, note = "home"),
            SystemRecord("X1-MF53", Stage.SETTLE, gateBuilt = true),
        ).associateBy { it.symbol })
        val grown = Strategy.growFleet(plan)
        assertEquals(Strategy.FREIGHTERS, grown.goals.fleet.first { it.type == ShipType.SHIP_HEAVY_FREIGHTER }.count)
        assertEquals(Strategy.EXPLORERS, grown.goals.fleet.first { it.type == ShipType.SHIP_EXPLORER }.count)
        assertEquals(grown, Strategy.growFleet(grown), "added once; the goals themselves stop the buying")
        val frigate = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val explorer = frigate.copy(symbol = "E-1", modules = frigate.modules + model.ship.components.Module("MODULE_WARP_DRIVE_I", "Warp Drive I", "", 0, frigate.modules.first().requirements))
        assertEquals("warpChart", Strategy.defaultAssignment(Phase.BOOM, explorer, snap.copy(plan = grown))?.behaviour)
        val freighter = frigate.copy(symbol = "F-1", mounts = emptyList(), cargo = frigate.cargo.copy(capacity = 225))
        // Only home has priced markets in the fixture, and home is excluded: the freighter trades without a system.
        assertEquals("trade", Strategy.defaultAssignment(Phase.BOOM, freighter, snap.copy(plan = grown))?.behaviour)
    }

    @Test
    fun `the probe goal follows the frontier and home's spare traders spread two per opened system, one move a tick`() {
        val snap = snap()
        val frigate = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val haulers = (1..5).map { i -> frigate.copy(symbol = "H-$i", mounts = emptyList(), cargo = frigate.cargo.copy(capacity = 80)) }
        val fleet = snap.copy(ships = snap.ships + haulers.associateBy { it.symbol })
        val plan = Plan(
            assignments = listOf(Assignment(Fixtures.COMMAND_SHIP, "trade")) + haulers.map { Assignment(it.symbol, "trade") },
            phase = Phase.BOOM,
            systems = listOf(
                SystemRecord("X1-TH77", Stage.SETTLE, gateBuilt = true, note = "home"),
                SystemRecord("X1-MF53", Stage.RUSH, gate = "X1-MF53-I57", gateBuilt = true, arrivedAt = now.toString()),
                SystemRecord("X1-XX21", Stage.CASCADE, gate = "X1-XX21-Z25C", gateBuilt = true, arrivedAt = now.plusSeconds(60).toString()),
            ).associateBy { it.symbol },
        ).withFrontier((1..5).map { FrontierGate("X1-Q$it-A1", "X1-TH77") })
        assertEquals(5, Strategy.pioneerRoom(plan))
        assertEquals(6, Strategy.growProbes(plan, fleet).goals.fleet.first { it.type == model.ship.ShipType.SHIP_PROBE && it.system == null }.count)
        val withKit = plan.withGoal(FleetGoal(ShipType.SHIP_PROBE, 2, system = "X1-MF53"))
        assertEquals(8, Strategy.growProbes(withKit, fleet).goals.fleet.first { it.type == model.ship.ShipType.SHIP_PROBE && it.system == null }.count, "a system's kit probes count on top")
        val withParked = withKit.with(Assignment("P-9", "park")).with(Assignment("P-8", "park"))
        assertEquals(6, Strategy.growProbes(withParked, fleet).goals.fleet.first { it.type == model.ship.ShipType.SHIP_PROBE && it.system == null }.count, "two parked probes mean two fewer to buy")
        val once = Strategy.spreadHaulers(plan, fleet)
        assertEquals("X1-MF53", once.assignmentFor("H-5")?.params?.get("system"), "the newest hauler goes to the entered system, not the one still in cascade")
        val twice = Strategy.spreadHaulers(once, fleet)
        assertEquals("X1-MF53", twice.assignmentFor("H-4")?.params?.get("system"))
        assertEquals(twice, Strategy.spreadHaulers(twice, fleet), "two per system, and no other system is open")
        assertEquals(4, twice.assignments.count { it.behaviour == "trade" && it.params["system"] == null }, "the frigate and three haulers stay home")
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

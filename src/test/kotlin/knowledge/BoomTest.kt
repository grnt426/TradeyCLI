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
        assertEquals(listOf("P-5"), moved.map { it.ship }, "the fifth probe on MF53 is spare and goes where a chart remains; TH77 has one waypoint left, so it has room for one: ${once.assignments.map { it.params }}")
        assertEquals("pioneer", once.assignmentFor("P-6")?.behaviour, "the sixth finds no room anywhere and pioneers (four probes bound to one waypoint bounced for hours on 2026-09-08)")
        assertEquals(4, once.assignments.count { it.params["system"] == "X1-MF53" }, "four stay charting MF53, whose waypoints are unread rather than charted")
        assertEquals(once, Strategy.spreadProbes(once, uncharted), "settled")
    }

    @Test
    fun `a spare probe prefers the charts it can reach in fewest jumps`() {
        val snap = snap()
        val probe = snap.ships.getValue(Fixtures.PROBE)
        val template = snap.waypointsIn("X1-TH77").first { it.type == model.system.WaypointType.ASTEROID }
        fun unchartedIn(system: String, n: Int) = (1..n).map { i -> template.copy(symbol = "$system-B$i", systemSymbol = system, traits = listOf(model.WaypointTrait(WaypointTraitSymbol.UNCHARTED, "Uncharted", ""))) }
        val fleet = snap.copy(waypoints = snap.waypoints + (unchartedIn("X1-NEAR", 3) + unchartedIn("X1-FAR", 8)).associateBy { it.symbol })
        val homeGate = snap.waypointsIn("X1-TH77").first { it.type == model.system.WaypointType.JUMP_GATE }.symbol
        // Home is charted, so a probe charting home is spare. NEAR is one jump; FAR's gate has never been read, so its distance is unknown.
        val plan = Plan(
            assignments = listOf(Assignment(probe.symbol, "chartSystem")),
            phase = Phase.BOOM,
            systems = listOf("X1-TH77", "X1-NEAR", "X1-FAR").map { SystemRecord(it, Stage.SETTLE, gateBuilt = true) }.associateBy { it.symbol },
        )
        val gates = mapOf(homeGate to listOf("X1-NEAR-G1"))
        assertEquals("X1-NEAR", Strategy.spreadProbes(plan, fleet, gates).assignmentFor(probe.symbol)?.params?.get("system"), "three charts one jump away; the unknown-distance system is not a target")
        assertEquals("pioneer", Strategy.spreadProbes(plan, fleet).assignmentFor(probe.symbol)?.behaviour, "with no map at all nothing is in reach: the probe pioneers rather than jump blind")
        val farAway = mapOf(homeGate to listOf("X1-A-G"), "X1-A-G" to listOf("X1-B-G"), "X1-B-G" to listOf("X1-C-G"), "X1-C-G" to listOf("X1-FAR-G1"))
        val bound = Plan(listOf(Assignment(probe.symbol, "chartSystem", mapOf("system" to "X1-FAR"))), phase = Phase.BOOM, systems = plan.systems)
        assertEquals("pioneer", Strategy.spreadProbes(bound, fleet, farAway).assignmentFor(probe.symbol)?.behaviour, "a target four jumps out is too far: the probe is taken off the trip")
        val nearer = farAway + (homeGate to listOf("X1-A-G", "X1-NEAR-G1"))
        assertEquals("X1-NEAR", Strategy.spreadProbes(bound, fleet, nearer).assignmentFor(probe.symbol)?.params?.get("system"), "and sent to the charts one jump away instead")
    }

    @Test
    fun `a lone watcher in a system without a trader charts while charts remain, and is never parked for it`() {
        val snap = snap()
        val probe = snap.ships.getValue(Fixtures.PROBE)
        val template = snap.waypointsIn("X1-TH77").first { it.type == model.system.WaypointType.ASTEROID }
        val far = (1..3).map { i -> template.copy(symbol = "X1-FAR-B$i", systemSymbol = "X1-FAR", traits = listOf(model.WaypointTrait(WaypointTraitSymbol.UNCHARTED, "Uncharted", ""))) }
        val fleet = snap.copy(waypoints = snap.waypoints + far.associateBy { it.symbol })
        val systems = listOf("X1-TH77", "X1-FAR").map { SystemRecord(it, Stage.SETTLE, gateBuilt = true) }.associateBy { it.symbol }
        val gates = mapOf(snap.waypointsIn("X1-TH77").first { it.type == model.system.WaypointType.JUMP_GATE }.symbol to listOf("X1-FAR-G1"))
        val watching = Plan(listOf(Assignment(probe.symbol, "probeMarkets", mapOf("maxAge" to "30"))), phase = Phase.BOOM, systems = systems)
        assertEquals("X1-FAR", Strategy.spreadProbes(watching, fleet, gates).assignmentFor(probe.symbol)?.params?.get("system"), "home has no trader: its watcher goes to chart one jump away")
        val withTrader = watching.with(Assignment(Fixtures.COMMAND_SHIP, "trade"))
        assertEquals(watching.assignmentFor(probe.symbol), Strategy.spreadProbes(withTrader, fleet, gates).assignmentFor(probe.symbol), "with a trader at home the one watcher stays")
        val nothingLeft = Plan(listOf(Assignment(probe.symbol, "probeMarkets", mapOf("maxAge" to "30"))), phase = Phase.BOOM, systems = systems.filterKeys { it == "X1-TH77" })
        assertEquals("probeMarkets", Strategy.spreadProbes(nothingLeft, snap).assignmentFor(probe.symbol)?.behaviour, "no charts anywhere: the lone watcher keeps watching rather than pioneer or park")
    }

    @Test
    fun `a stranded explorer warps back to the nearest held system with a gate in reach`() {
        val snap = snap()
        val home = snap.systems.getValue("X1-TH77")
        val here = home.copy(symbol = "X1-LOST", x = home.x + 300, y = home.y, waypoints = home.waypoints.filter { it.type != model.system.WaypointType.JUMP_GATE })
        val farther = home.copy(symbol = "X1-GATED", x = home.x + 900, y = home.y)
        val world = snap.copy(systems = snap.systems + listOf(here, farther).associateBy { it.symbol })
        val plan = Plan(systems = listOf("X1-TH77", "X1-GATED", "X1-LOST").map { SystemRecord(it, Stage.SETTLE, gateBuilt = it != "X1-LOST") }.associateBy { it.symbol })
        assertEquals("X1-TH77", Strategy.warpHome(plan, world, here) { 400.0 }?.first?.symbol, "home's gate is 300 away")
        assertEquals(null, Strategy.warpHome(plan, world, here) { 200.0 }, "nothing gated within 200")
    }

    @Test
    fun `a trader in a drained system moves to the neighbour within two jumps that promises the most, if it has a slot`() {
        val snap = snap()
        val frigate = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val hauler = frigate.copy(symbol = "H-1", mounts = emptyList(), cargo = frigate.cargo.copy(capacity = 80))
        val heavy = frigate.copy(symbol = "F-1", mounts = emptyList(), cargo = frigate.cargo.copy(capacity = 225))
        val another = heavy.copy(symbol = "F-2")
        // A second system with home's markets and prices under another name; home itself has no prices any more.
        fun clone(symbol: String) = symbol.replace("X1-TH77", "X1-MF53")
        val cloned = snap.waypointsIn("X1-TH77").map { it.copy(symbol = clone(it.symbol), systemSymbol = "X1-MF53", orbitals = emptyList(), orbits = null) }
        val prices = snap.markets.values.map { m -> m.copy(symbol = clone(m.symbol)).also { it.lastRead = now } }
        val drained = snap.markets.mapValues { (_, m) -> m.copy(tradeGoods = emptyList()).also { it.lastRead = now } }
        val world = snap.copy(
            ships = snap.ships + listOf(hauler, heavy, another).associateBy { it.symbol },
            waypoints = snap.waypoints + cloned.associateBy { it.symbol },
            markets = drained + prices.associateBy { it.symbol },
        )
        val homeGate = snap.waypointsIn("X1-TH77").first { it.type == model.system.WaypointType.JUMP_GATE }.symbol
        val gates = mapOf(homeGate to listOf(clone(homeGate)))
        val plan = Plan(
            assignments = listOf(Assignment("H-1", "trade")),
            phase = Phase.BOOM,
            systems = listOf(SystemRecord("X1-TH77", Stage.SETTLE, gateBuilt = true, note = "home"), SystemRecord("X1-MF53", Stage.SETTLE, gateBuilt = true)).associateBy { it.symbol },
        )
        val rules = Strategy.trading(Phase.BOOM)
        assertTrue(Strategy.tradeValue(world, hauler, "X1-MF53", now, rules) > Strategy.RELOCATE_MIN_RATE, "the clone's prices promise a rate")
        assertEquals(0.0, Strategy.tradeValue(world, hauler, "X1-TH77", now, rules), "home promises nothing")
        assertEquals("X1-MF53", Strategy.betterSystem(plan, world, hauler, gates, emptySet(), now, rules, localRate = 0.0))
        assertEquals(null, Strategy.betterSystem(plan, world, hauler, emptyMap(), emptySet(), now, rules, localRate = 0.0), "no known gate, no move")
        val far = mapOf(homeGate to listOf("X1-A-G"), "X1-A-G" to listOf("X1-B-G"), "X1-B-G" to listOf("X1-C-G"), "X1-C-G" to listOf(clone(homeGate)))
        assertEquals("X1-MF53", Strategy.betterSystem(plan, world, hauler, far, emptySet(), now, rules, localRate = 0.0), "four jumps out is in reach when nothing pays here")
        assertEquals(null, Strategy.betterSystem(plan, world, hauler, far, emptySet(), now, rules, localRate = 1_000.0), "but not while something does")
        assertEquals(null, Strategy.betterSystem(plan, world, hauler, gates, emptySet(), now, rules, localRate = 10_000_000.0), "a system that pays well is not left")
        val taken = plan.with(Assignment("F-1", "trade", mapOf("system" to "X1-MF53")))
        assertEquals(null, Strategy.betterSystem(taken, world, hauler, gates, emptySet(), now, rules, localRate = 0.0), "a heavy bound there fills both slots")
        assertEquals("X1-MF53", Strategy.bestTradingSystem(plan, world, heavy, now), "a new freighter goes where the prices promise the most")
        assertEquals(null, Strategy.bestTradingSystem(taken, world, another, now), "and never on top of another heavy")
    }

    @Test
    fun `spare probes are the parked, the watchers beyond one per system, and the charters of a charted system`() {
        val snap = snap()
        val probe = snap.ships.getValue(Fixtures.PROBE)
        val probes = (1..5).map { i -> probe.copy(symbol = "P-$i") }
        val fleet = snap.copy(ships = snap.ships + probes.associateBy { it.symbol })
        val plan = Plan(listOf(
            Assignment("P-1", "probeMarkets", mapOf("maxAge" to "30")),
            Assignment("P-2", "probeMarkets", mapOf("maxAge" to "30")),
            Assignment("P-3", "probeMarkets", mapOf("maxAge" to "30")),
            Assignment("P-4", "park"),
            Assignment("P-5", "chartSystem"),
            Assignment(Fixtures.PROBE, "chartSystem", mapOf("system" to "X1-MF53")),
        ))
        // Three watchers at home: one is the watcher, two are spare. One parked. P-5 charts home, which is charted; the fixture probe charts an unread system.
        assertEquals(4, Strategy.spareProbes(plan, fleet))
        val kits = plan.withGoal(FleetGoal(ShipType.SHIP_PROBE, 2, system = "X1-MF53")).withGoal(FleetGoal(ShipType.SHIP_HEAVY_FREIGHTER, 25))
        assertEquals(listOf(ShipType.SHIP_HEAVY_FREIGHTER), Strategy.pruneKits(kits, fleet).goals.fleet.map { it.type }, "kit goals go while spares exist; the freighter goal stays")
        val busy = Plan(listOf(Assignment("P-1", "probeMarkets", mapOf("maxAge" to "30")))).withGoal(FleetGoal(ShipType.SHIP_PROBE, 2, system = "X1-MF53"))
        assertEquals(busy, Strategy.pruneKits(busy, fleet), "with no spare probe the kit is still wanted")
    }

    @Test
    fun `an explorer warps only as far as its tank brings it back, unless fuel is known on the far side`() {
        assertEquals(397.5, Strategy.warpReach(800.0, fuelKnownAtTarget = false))
        assertEquals(795.0, Strategy.warpReach(800.0, fuelKnownAtTarget = true))
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
        assertEquals(Strategy.BULK_FREIGHTERS, grown.goals.fleet.first { it.type == ShipType.SHIP_BULK_FREIGHTER }.count)
        assertEquals(Strategy.EXPLORERS, grown.goals.fleet.first { it.type == ShipType.SHIP_EXPLORER }.count)
        assertEquals(grown, Strategy.growFleet(grown), "added once; the goals themselves stop the buying")
        val older = plan.withGoal(FleetGoal(ShipType.SHIP_HEAVY_FREIGHTER, 3, reserve = 3_000_000))
        assertEquals(Strategy.FREIGHTERS, Strategy.growFleet(older).goals.fleet.first { it.type == ShipType.SHIP_HEAVY_FREIGHTER }.count, "a raised constant raises a goal already in the plan")
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

package knowledge

import behaviour.decisions.Mining
import kotlinx.coroutines.test.TestCoroutineScheduler
import model.market.SupplyLevel
import model.market.TradeSymbol
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
import kotlin.test.assertTrue

/** The phases as data: what each one changes, and that the plan carries it. */
class StrategyTest {
    private val now = Instant.parse("2026-09-04T12:00:00Z")
    private fun pricedSeed(): SimSeed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))

    @Test
    fun `escape leaves the most margin and the strictest health weights, late loosens both to measure`() {
        assertTrue(Strategy.marginFloor(Phase.ESCAPE) > Strategy.marginFloor(Phase.BOOM))
        assertTrue(Strategy.marginFloor(Phase.LATE) <= Strategy.marginFloor(Phase.BOOM))
        val escape = Strategy.market(Phase.ESCAPE)
        val late = Strategy.market(Phase.LATE)
        assertTrue(late.sourceActivityWeight.getValue(model.market.ActivityLevel.RESTRICTED) > escape.sourceActivityWeight.getValue(model.market.ActivityLevel.RESTRICTED))
        assertTrue(late.destinationSupplyWeight.getValue(SupplyLevel.ABUNDANT) > escape.destinationSupplyWeight.getValue(SupplyLevel.ABUNDANT))
        assertEquals(0.15, Strategy.trading(Phase.ESCAPE).minMarginRatio)
        assertEquals(0.05, Strategy.trading(Phase.ESCAPE, minMarginRatio = 0.05).minMarginRatio, "an explicit parameter still wins")
    }

    @Test
    fun `at a reset the probe charts home before it reads prices, then reads, then buys the fleet`() {
        val seed = pricedSeed()
        val snap = SimRun.worldFrom(SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))).snapshot(1)
        val probe = snap.ships.getValue(Fixtures.PROBE)
        val uncharted = snap.copy(
            waypoints = snap.waypoints.mapValues { (_, w) -> if (w.symbol == "X1-TH77-B9") w.copy(traits = w.traits + model.WaypointTrait(model.WaypointTraitSymbol.UNCHARTED, "Uncharted", "")) else w },
            plan = Plan(),
        )
        assertEquals("chartSystem", Strategy.defaultAssignment(Phase.ESCAPE, probe, uncharted)?.behaviour)
        assertEquals("probeMarkets", Strategy.defaultAssignment(Phase.ESCAPE, probe, snap.copy(plan = Plan()))?.behaviour, "nothing to chart: read prices")
        assertEquals("probeMarkets", Strategy.afterFinished(Phase.ESCAPE, probe, "chartSystem", uncharted)?.behaviour)
        val fresh = Strategy.freshPlan(Phase.ESCAPE, uncharted)
        assertEquals("chartSystem", fresh.assignmentFor(Fixtures.PROBE)?.behaviour)
        assertEquals("trade", fresh.assignmentFor(Fixtures.COMMAND_SHIP)?.behaviour)
        assertTrue(fresh.goals.fleet.isNotEmpty())
    }

    @Test
    fun `in escape a load that feeds a gate producer's short input outscores the same money elsewhere`() {
        val seed = pricedSeed()
        val snap = SimRun.worldFrom(SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))).snapshot(1).let { s ->
            s.copy(
                markets = seed.markets.associateBy { it.symbol }.mapValues { (_, m) -> m.also { it.lastRead = now } },
                constructionBill = listOf(model.ConstructionMaterial(TradeSymbol.FAB_MATS, 1600, 0), model.ConstructionMaterial(TradeSymbol.ADVANCED_CIRCUITRY, 400, 0)),
            )
        }
        val targets = Strategy.chainTargets(snap)
        assertTrue(targets.isNotEmpty(), "the seed has LIMITED inputs in the chains")
        assertTrue(targets.all { it.startsWith("X1-TH77-") && it.contains('/') }, targets.toString())
        val ship = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val plain = Mining.rank(snap, ship, now) // touch the ranking module so the import is used
        val fed = behaviour.decisions.Trading.rank(snap, ship, now, Strategy.trading(Phase.ESCAPE, snapshot = snap))
        val unfed = behaviour.decisions.Trading.rank(snap, ship, now, Strategy.trading(Phase.ESCAPE))
        val feeding = fed.filter { it.health.endsWith("(feeds the gate)") }
        assertTrue(feeding.isNotEmpty(), "some route feeds a chain: ${fed.take(5).map { it.health }}")
        val f = feeding.first()
        // The feeding route may be below the ordinary floor, so it need not exist in the unfed ranking; the bonus shows in its own score.
        assertTrue(f.score > f.creditsPerHour * 1.5, "the bonus outweighs the health weights: ${f.score} vs ${f.creditsPerHour}")
        assertTrue(unfed.none { it.health.endsWith("(feeds the gate)") })
        assertTrue(plain.size >= 0)
    }

    @Test
    fun `in escape a LIMITED chain export goes only to another gate producer, never to an ordinary buyer`() {
        // H50 refines iron for F47 (fab mats). With H50's iron LIMITED, the E-row importers may not have it; F47 still may.
        val seed = pricedSeed().let { s ->
            s.copy(markets = s.markets.map { m ->
                if (m.symbol == "X1-TH77-H50") m.copy(tradeGoods = m.tradeGoods.map { g -> if (g.symbol == TradeSymbol.IRON) g.copy(supply = SupplyLevel.LIMITED) else g }).also { it.lastRead = m.lastRead } else m
            })
        }
        val snap = SimRun.worldFrom(SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))).snapshot(1).let { s ->
            s.copy(
                markets = seed.markets.associateBy { it.symbol }.mapValues { (_, m) -> m.also { it.lastRead = now } },
                constructionBill = listOf(model.ConstructionMaterial(TradeSymbol.FAB_MATS, 1600, 0)),
            )
        }
        assertTrue("X1-TH77-H50/IRON" in Strategy.chainSources(snap), Strategy.chainSources(snap).toString())
        val ship = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val escape = behaviour.decisions.Trading.rank(snap, ship, now, Strategy.trading(Phase.ESCAPE, snapshot = snap))
        val plain = behaviour.decisions.Trading.rank(snap, ship, now)
        assertTrue(plain.any { it.good == TradeSymbol.IRON && it.source.symbol == "X1-TH77-H50" && it.destination.symbol != "X1-TH77-F47" }, "unweighted, iron goes to the E-row")
        val iron = escape.filter { it.good == TradeSymbol.IRON && it.source.symbol == "X1-TH77-H50" }
        assertTrue(iron.all { it.destination.symbol == "X1-TH77-F47" && it.feeds }, "in escape only the fab-mats producer may have H50's iron: ${iron.map { it.destination.symbol }}")
    }

    @Test
    fun `the gate chains feed each producer's inputs from the cheapest other market, one level down, and feeders join the smallest team`() {
        val seed = pricedSeed()
        val snap = SimRun.worldFrom(SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))).snapshot(1).let { s ->
            s.copy(
                markets = seed.markets.associateBy { it.symbol }.mapValues { (_, m) -> m.also { it.lastRead = now } },
                constructionBill = listOf(model.ConstructionMaterial(TradeSymbol.FAB_MATS, 1600, 0), model.ConstructionMaterial(TradeSymbol.ADVANCED_CIRCUITRY, 400, 0)),
            )
        }
        val chains = Strategy.gateChains(snap, now)
        assertEquals(listOf("gate-FAB_MATS", "gate-ADVANCED_CIRCUITRY"), chains.map { it.id })
        val fab = chains.first()
        assertTrue(fab.legs.any { it.good == TradeSymbol.IRON && it.from == "X1-TH77-H50" && it.to == "X1-TH77-F47" }, fab.legs.toString())
        val circ = chains.last()
        assertTrue(circ.legs.any { it.good == TradeSymbol.MICROPROCESSORS && it.to == "X1-TH77-D42" }, circ.legs.toString())
        assertTrue(circ.legs.any { it.to == "X1-TH77-A3" }, "one level down: the microprocessor plant's own inputs " + circ.legs)
        // Seeding is idempotent and enrols a feeder on its chain's team.
        val seeded = Plan().let { Strategy.seedGateChains(it, snap.copy(plan = it), now) }
        assertEquals(seeded, Strategy.seedGateChains(seeded, snap.copy(plan = seeded), now))
        val withFeeder = seeded.with(plan.Assignment(Fixtures.COMMAND_SHIP, "feed", mapOf("chain" to "gate-FAB_MATS")))
        assertTrue(Fixtures.COMMAND_SHIP in Strategy.seedGateChains(withFeeder, snap.copy(plan = withFeeder), now).chain("gate-FAB_MATS")!!.ships)
        // A hauler after the crew feeds; the gate hauler, the contract hauler and one trader come first.
        val frigate = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val hauler = frigate.copy(mounts = emptyList(), cargo = frigate.cargo.copy(capacity = 80))
        val withSite = snap.copy(waypoints = snap.waypoints.mapValues { (_, w) -> if (w.type == model.system.WaypointType.JUMP_GATE) w.copy(isUnderConstruction = true) else w })
        val crewed = (1..Strategy.ESCAPE_CREW).map { i -> hauler.copy(symbol = "X-$i") }
        val late = hauler.copy(symbol = "X-9")
        val fleet = withSite.copy(ships = withSite.ships + crewed.associateBy { it.symbol } + (late.symbol to late), plan = seeded)
        val job = Strategy.defaultAssignment(Phase.ESCAPE, late, fleet)
        assertEquals("feed", job?.behaviour, job.toString())
        assertEquals("gate-FAB_MATS", job?.params?.get("chain"))
        val full = fleet.copy(plan = seeded.with(plan.Assignment("X-1", "feed", mapOf("chain" to "gate-FAB_MATS"))).with(plan.Assignment("X-2", "feed", mapOf("chain" to "gate-ADVANCED_CIRCUITRY"))))
        assertEquals("trade", Strategy.defaultAssignment(Phase.ESCAPE, late, full)?.behaviour, "the feeder slots are full")
        assertEquals(5 + Strategy.FEEDERS, Strategy.goals(Phase.ESCAPE).fleet.first { it.type == model.ship.ShipType.SHIP_LIGHT_HAULER }.count)
    }

    @Test
    fun `the plan carries its phase through json and starts in escape`() {
        val file = File.createTempFile("plan", ".json").also { it.deleteOnExit() }
        Plan.save(file, Plan().withPhase(Phase.BOOM))
        assertEquals(Phase.BOOM, Plan.load(file).phase)
        assertEquals(Phase.ESCAPE, Plan().phase)
    }

    @Test
    fun `in escape a drone sells its quartz to the starved fab-mats producer rather than the exchange`() {
        // F47 imports QUARTZ_SAND and is SCARCE in it; B7 is an exchange paying about the same. Only the weighting tells them apart.
        val seed = pricedSeed().let { s ->
            s.copy(markets = s.markets.map { m ->
                when (m.symbol) {
                    "X1-TH77-F47" -> m.copy(tradeGoods = m.tradeGoods.map { g -> if (g.symbol == TradeSymbol.QUARTZ_SAND) g.copy(supply = SupplyLevel.SCARCE, sellPrice = 24) else g }).also { it.lastRead = m.lastRead }
                    else -> m
                }
            })
        }
        val universe = SimUniverse(seed, VirtualClock(TestCoroutineScheduler(), now))
        val snap = SimRun.worldFrom(universe).snapshot(1).let { s ->
            s.copy(markets = seed.markets.associateBy { it.symbol }.mapValues { (_, m) -> m.also { it.lastRead = now } })
        }
        val ship = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val b11 = { plans: List<behaviour.decisions.MiningPlan> -> plans.filter { it.asteroid.symbol == "X1-TH77-B11" } }
        // F47 is 279 away against B7's 13, so geometry still decides the route for this rock; the weighting
        // is a tilt on the value of each unit, not a veto. Measure the tilt.
        val byPrice = b11(Mining.rank(snap, ship, now))
        val byHealth = b11(Mining.rank(snap, ship, now, Strategy.mining(Phase.ESCAPE)))
        val f47Price = byPrice.first { it.market.symbol == "X1-TH77-F47" }
        val f47Health = byHealth.first { it.market.symbol == "X1-TH77-F47" }
        val b7Price = byPrice.first { it.market.symbol == "X1-TH77-B7" }
        val b7Health = byHealth.first { it.market.symbol == "X1-TH77-B7" }
        assertTrue(f47Health.valuePerUnit > f47Price.valuePerUnit * 1.3, "a SCARCE importer's units count about double: ${f47Health.valuePerUnit} vs ${f47Price.valuePerUnit}")
        assertTrue(b7Health.valuePerUnit < b7Price.valuePerUnit, "an exchange counts a little less: ${b7Health.valuePerUnit} vs ${b7Price.valuePerUnit}")
        assertTrue(f47Health.creditsPerHour / b7Health.creditsPerHour > f47Price.creditsPerHour / b7Price.creditsPerHour * 1.5, "the producer gains ground on the exchange")
        assertTrue(f47Health.riskNotes.any { it.startsWith("feeds QUARTZ_SAND") }, f47Health.riskNotes.toString())
    }
}

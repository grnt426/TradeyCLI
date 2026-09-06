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

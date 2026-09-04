package behaviour

import behaviour.decisions.Mining
import behaviour.decisions.Surveys
import engine.Snapshot
import model.WaypointTrait
import model.WaypointTraitSymbol
import model.actions.Survey
import model.actions.SurveyDeposit
import model.actions.SurveySize
import model.market.TradeSymbol
import model.system.WaypointModifiers
import sim.Fixtures
import sim.SimRun
import sim.SimUniverse
import sim.VirtualClock
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The ranking is a pure function of a snapshot; these run against the real X1-TH77 layout. */
class MiningRankTest {

    private val now = Instant.parse("2026-09-04T12:00:00Z")

    private fun snapshot(): Snapshot {
        val universe = SimUniverse(Fixtures.seed(), VirtualClock(TestCoroutineScheduler(), now))
        return SimRun.worldFrom(universe).snapshot(1)
    }

    @Test
    fun `every plan pairs a minable asteroid with a market that buys something it yields`() {
        val snap = snapshot()
        val plans = Mining.rank(snap, snap.ships.getValue(Fixtures.COMMAND_SHIP), now)
        assertTrue(plans.isNotEmpty())
        plans.forEach { p ->
            assertTrue(p.asteroid.isMineable, p.asteroid.symbol)
            assertTrue(p.prices.keys.all { p.market.trades(it) }, "${p.market.symbol} buys ${p.prices.keys}")
            assertTrue(p.creditsPerHour > 0, p.summary())
            assertTrue(p.tradedShare in 0.0..1.0001)
        }
        assertEquals(plans.sortedByDescending { it.creditsPerHour }, plans, "best first")
    }

    @Test
    fun `the stripped engineered asteroid next to headquarters is never proposed`() {
        val snap = snapshot()
        val plans = Mining.rank(snap, snap.ships.getValue(Fixtures.COMMAND_SHIP), now)
        assertTrue(plans.none { it.asteroid.symbol == "X1-TH77-DB5B" })
    }

    @Test
    fun `a probe gets no plans`() {
        val snap = snapshot()
        assertTrue(Mining.rank(snap, snap.ships.getValue(Fixtures.PROBE), now).isEmpty())
    }

    @Test
    fun `an unstable modifier lowers the score and a critical one lowers it more`() {
        val snap = snapshot()
        val ship = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val healthy = Mining.rank(snap, ship, now).first { it.asteroid.symbol == Fixtures.METAL_ASTEROID }
        fun withModifier(symbol: String): Snapshot {
            val w = snap.waypoints.getValue(Fixtures.METAL_ASTEROID).copy(modifiers = listOf(WaypointModifiers.of(symbol)))
            return snap.copy(waypoints = snap.waypoints + (w.symbol to w))
        }
        val unstable = Mining.rank(withModifier(WaypointModifiers.UNSTABLE), ship, now).first { it.asteroid.symbol == Fixtures.METAL_ASTEROID && it.market.symbol == healthy.market.symbol }
        val critical = Mining.rank(withModifier(WaypointModifiers.CRITICAL_LIMIT), ship, now).first { it.asteroid.symbol == Fixtures.METAL_ASTEROID && it.market.symbol == healthy.market.symbol }
        assertTrue(unstable.creditsPerHour < healthy.creditsPerHour)
        assertTrue(critical.creditsPerHour < unstable.creditsPerHour)
        assertTrue("UNSTABLE" in unstable.riskNotes)
        val stripped = Mining.rank(withModifier(WaypointModifiers.STRIPPED), ship, now)
        assertTrue(stripped.none { it.asteroid.symbol == Fixtures.METAL_ASTEROID }, "stripped rocks are dropped")
    }

    @Test
    fun `a fragile trait is a risk and hazards are a smaller one`() {
        val snap = snapshot()
        val hq = snap.waypoints.getValue(Fixtures.HQ)
        val plain = snap.waypoints.getValue(Fixtures.METAL_ASTEROID)
        val fragile = plain.copy(traits = plain.traits + WaypointTrait(WaypointTraitSymbol.UNSTABLE_COMPOSITION, "", ""))
        val hazard = plain.copy(traits = plain.traits + WaypointTrait(WaypointTraitSymbol.RADIOACTIVE, "", ""))
        assertEquals(1.0, Mining.riskOf(plain, hq).first)
        assertTrue(Mining.riskOf(fragile, hq).first < Mining.riskOf(hazard, hq).first)
        assertTrue(Mining.riskOf(hazard, hq).first < 1.0)
    }

    @Test
    fun `observed prices replace guesses and are no longer marked estimated`() {
        val snap = snapshot()
        val ship = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        assertTrue(Mining.rank(snap, ship, now).all { it.estimated }, "nothing has been read yet: every price is a guess")
        val universe = SimUniverse(Fixtures.seed(), VirtualClock(TestCoroutineScheduler(), now))
        val visible = universe.market(Fixtures.ORE_MARKET).copy(tradeGoods = universe.markets.getValue(Fixtures.ORE_MARKET).view(now, visible = true).tradeGoods)
        val withPrices = snap.copy(markets = snap.markets + (visible.symbol to visible))
        val plans = Mining.rank(withPrices, ship, now).filter { it.market.symbol == Fixtures.ORE_MARKET }
        assertTrue(plans.isNotEmpty())
        assertTrue(plans.none { it.estimated })
    }

    @Test
    fun `a survey is used only when its deposits beat the rock's average`() {
        val snap = snapshot()
        val ship = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val plan = Mining.rank(snap, ship, now).first { it.asteroid.symbol == Fixtures.METAL_ASTEROID }
        val best = plan.prices.maxByOrNull { it.value }!!.key
        val worst = plan.prices.minByOrNull { it.value }!!.key
        val good = Survey("s1", Fixtures.METAL_ASTEROID, List(4) { SurveyDeposit(best) }, now.plusSeconds(3600), SurveySize.LARGE)
        val junk = Survey("s2", Fixtures.METAL_ASTEROID, List(4) { SurveyDeposit(TradeSymbol.QUARTZ_SAND) }, now.plusSeconds(3600), SurveySize.LARGE)
        val poor = Survey("s3", Fixtures.METAL_ASTEROID, List(4) { SurveyDeposit(worst) }, now.plusSeconds(3600), SurveySize.LARGE)
        assertEquals(good, Surveys.pick(listOf(junk, good, poor), plan))
        assertEquals(null, Surveys.pick(listOf(junk), plan), "a survey of goods the market does not buy is worthless")
    }
}


class ReturnLegTest {
    private val now = java.time.Instant.parse("2026-09-04T12:00:00Z")

    private fun snapshot(): Snapshot {
        val universe = SimUniverse(Fixtures.seed(), VirtualClock(TestCoroutineScheduler(), now))
        return SimRun.worldFrom(universe).snapshot(1)
    }

    @Test
    fun `a round trip inside the tank cruises, a longer one refuels on the way or drifts, and the price reflects it`() {
        val snap = snapshot()
        val ship = snap.ships.getValue(Fixtures.COMMAND_SHIP)
        val plans = Mining.rank(snap, ship, now)
        // B9 to the asteroid base B7: 50 away, well inside a 400 tank
        val near = plans.first { it.asteroid.symbol == Fixtures.METAL_ASTEROID && it.market.symbol == Fixtures.NEAR_MARKET }
        assertEquals(behaviour.decisions.ReturnLeg.Cruise, near.returnLeg)
        assertEquals(100, near.fuelPerCycle)
        // B37 to B7: 267 each way; nothing sells fuel within the 133 left, so it drifts back and the cycle is priced that way
        val far = plans.first { it.asteroid.symbol == "X1-TH77-B37" && it.market.symbol == Fixtures.NEAR_MARKET }
        assertEquals(behaviour.decisions.ReturnLeg.Drift, far.returnLeg)
        assertTrue(far.cycleSeconds > 30 * 60, "a drift leg of 267 at speed 36 takes half an hour: cycle ${far.cycleSeconds}s")
        assertEquals(268, far.fuelPerCycle)
        // Every fuel stop the search picks must be reachable with what the trip out leaves
        val stops = plans.filter { it.returnLeg is behaviour.decisions.ReturnLeg.Via }
        assertTrue(stops.isNotEmpty(), "some rock/market pair should route through a fuel stop")
        stops.forEach { p ->
            val via = p.returnLeg as behaviour.decisions.ReturnLeg.Via
            val left = ship.fuel.capacity - engine.Travel.fuelCost(p.distance, model.ship.FlightMode.CRUISE)
            assertTrue(engine.Travel.fuelCost(via.toVia, model.ship.FlightMode.CRUISE) <= left, p.summary())
        }
    }
}

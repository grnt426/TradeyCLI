package sim

import api.ApiClient
import api.GameApi
import api.RequestPacer
import api.SpaceTradersApi
import engine.ShipVerbs
import engine.VerbFailure
import engine.Verbs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import model.market.TradeSymbol
import model.ship.FlightMode
import model.ship.ShipNavStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The verbs behave the same whether the simulator answers directly or through HTTP and the real
 * client. Every case runs both ways; the HTTP way also exercises the wire format the live server
 * uses, so a decoding mistake shows up here rather than against the real API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerbConformanceTest {

    private class Rig(val verbs: Verbs, val universe: SimUniverse, val trace: TraceSink, val requests: () -> Int)

    private fun TestScope.direct(): Rig {
        val clock = VirtualClock(testScheduler, Instant.parse("2026-09-04T12:00:00Z"))
        val universe = SimUniverse(Fixtures.seed(), clock)
        val trace = TraceSink()
        val verbs = ShipVerbs(SimApi(universe), SimRun.worldFrom(universe), clock, trace)
        return Rig(verbs, universe, trace) { universe.calls }
    }

    private fun TestScope.overHttp(): Rig {
        val clock = VirtualClock(testScheduler, Instant.parse("2026-09-04T12:00:00Z"))
        val universe = SimUniverse(Fixtures.seed(), clock)
        val server = FakeServer(universe)
        // The pacer lives on real threads and real time: its periodic loops must not be virtual tasks,
        // or the scheduler would jump the clock ahead while Ktor's I/O thread is answering a request.
        val pacer = RequestPacer(pacerScope)
        val api: GameApi = SpaceTradersApi(ApiClient("token", pacer, server.engine))
        val trace = TraceSink()
        val verbs = ShipVerbs(api, SimRun.worldFrom(universe), clock, trace)
        return Rig(verbs, universe, trace) { server.requests }
    }

    private val pacerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun bothWays(block: suspend TestScope.(Rig) -> Unit) {
        runTest { block(direct()) }
        try {
            runTest { block(overHttp()) }
        } finally {
            pacerScope.cancel()
        }
    }

    @Test
    fun `dock and orbit are idempotent and cost nothing when already there`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        assertEquals(ShipNavStatus.DOCKED, rig.verbs.ship(ship).nav.status)
        val before = rig.requests()
        rig.verbs.dock(ship)
        assertEquals(before, rig.requests(), "docking a docked ship is free")
        rig.verbs.orbit(ship)
        assertEquals(ShipNavStatus.IN_ORBIT, rig.verbs.ship(ship).nav.status)
        assertEquals(before + 1, rig.requests())
        rig.verbs.orbit(ship)
        assertEquals(before + 1, rig.requests())
    }

    @Test
    fun `navigate consumes fuel, takes the wiki's time and arrives in orbit`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        val start = rig.verbs.clock.now()
        val before = rig.verbs.ship(ship)
        val arrived = rig.verbs.navigateTo(ship, Fixtures.ORE_MARKET)
        assertEquals(Fixtures.ORE_MARKET, arrived.nav.waypointSymbol)
        assertEquals(ShipNavStatus.IN_ORBIT, arrived.nav.status)
        // A1 (-25,3) to H50 (-17,-42): distance 45.7 -> 46 fuel, 46*25/36+15 = 47 s
        assertEquals(before.fuel.current - 46, arrived.fuel.current)
        val elapsed = rig.verbs.clock.now().epochSecond - start.epochSecond
        assertTrue(elapsed in 47..49, "elapsed $elapsed")
    }

    @Test
    fun `navigate refuses a trip the tank cannot cover`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        // J57 is at (479,539): over 700 away from A1, more than the 400 tank
        assertFailsWith<VerbFailure.InsufficientFuel> { rig.verbs.navigateTo(ship, "X1-TH77-J57") }
        assertEquals(Fixtures.HQ, rig.verbs.ship(ship).nav.waypointSymbol)
    }

    @Test
    fun `probes fly for free`() = bothWays { rig ->
        val probe = rig.verbs.navigateTo(Fixtures.PROBE, "X1-TH77-J57")
        assertEquals("X1-TH77-J57", probe.nav.waypointSymbol)
        assertEquals(0, probe.fuel.current)
    }

    @Test
    fun `extract waits out the cooldown, fills cargo and reports modifiers`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        rig.verbs.navigateTo(ship, Fixtures.METAL_ASTEROID)
        val first = rig.verbs.extract(ship)
        assertTrue(first.units > 0)
        assertTrue(first.good in setOf(TradeSymbol.IRON_ORE, TradeSymbol.COPPER_ORE, TradeSymbol.ALUMINUM_ORE), first.good.name)
        val t1 = rig.verbs.clock.now()
        val second = rig.verbs.extract(ship)
        val waited = rig.verbs.clock.now().epochSecond - t1.epochSecond
        assertTrue(waited >= 70, "second extraction waited $waited s for the cooldown")
        assertEquals(first.units + second.units, rig.verbs.ship(ship).cargo.units)
        assertTrue(second.modifiers.isEmpty(), "a fresh rock has no modifiers")
    }

    @Test
    fun `extract refuses a full hold and a ship without a laser`() = bothWays { rig ->
        assertFailsWith<VerbFailure.CannotMine> { rig.verbs.extract(Fixtures.PROBE) }
        val ship = Fixtures.COMMAND_SHIP
        rig.verbs.navigateTo(ship, Fixtures.METAL_ASTEROID)
        while (!rig.verbs.ship(ship).cargoFull) rig.verbs.extract(ship)
        assertFailsWith<VerbFailure.CargoFull> { rig.verbs.extract(ship) }
    }

    @Test
    fun `survey then extract with the survey yields one of its deposits`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        rig.verbs.navigateTo(ship, Fixtures.METAL_ASTEROID)
        val surveys = rig.verbs.survey(ship)
        assertTrue(surveys.isNotEmpty())
        assertEquals(surveys, rig.verbs.surveysFor(Fixtures.METAL_ASTEROID))
        val survey = surveys.first()
        val got = rig.verbs.extract(ship, survey)
        assertTrue(got.good in survey.goods, "${got.good} not in ${survey.goods}")
    }

    @Test
    fun `sell splits by trade volume, pays the market's price and books every transaction`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        rig.verbs.navigateTo(ship, Fixtures.METAL_ASTEROID)
        while (!rig.verbs.ship(ship).cargoFull) rig.verbs.extract(ship)
        val cargo = rig.verbs.ship(ship).cargo
        rig.verbs.navigateTo(ship, Fixtures.NEAR_MARKET)
        val market = rig.verbs.refreshMarket(Fixtures.NEAR_MARKET)
        assertTrue(market.hasPrices, "prices visible with a ship present")
        val creditsBefore = rig.verbs.agent().credits
        var earned = 0L
        cargo.inventory.forEach { line ->
            val sale = rig.verbs.sell(ship, line.symbol, line.units)
            assertEquals(line.units, sale.units)
            val volume = market.good(line.symbol)!!.tradeVolume
            assertEquals((line.units + volume - 1) / volume, sale.transactions.size, "batches of $volume")
            earned += sale.credits
        }
        assertEquals(creditsBefore + earned, rig.verbs.agent().credits)
        assertTrue(rig.verbs.ship(ship).cargo.isEmpty)
        assertEquals(cargo.inventory.sumOf { (it.units + market.good(it.symbol)!!.tradeVolume - 1) / market.good(it.symbol)!!.tradeVolume }, rig.trace.transactions.size)
    }

    @Test
    fun `sell refuses a good the market does not trade`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        rig.verbs.navigateTo(ship, Fixtures.METAL_ASTEROID)
        rig.verbs.extract(ship)
        rig.verbs.navigateTo(ship, Fixtures.HQ, FlightMode.DRIFT) // A1 imports food and the like, no ores; drift because the tank is low
        rig.verbs.refreshMarket(Fixtures.HQ)
        val good = rig.verbs.ship(ship).cargo.inventory.first().symbol
        assertFailsWith<VerbFailure.MarketRefuses> { rig.verbs.sell(ship, good, 1) }
    }

    @Test
    fun `refuel fills the tank, charges per hundred units and is free when full`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        val before = rig.requests()
        rig.verbs.refuel(ship)
        assertEquals(before, rig.requests(), "a full tank needs no request")
        rig.verbs.navigateTo(ship, Fixtures.ORE_MARKET)
        val credits = rig.verbs.agent().credits
        val after = rig.verbs.refuel(ship)
        assertEquals(after.fuel.capacity, after.fuel.current)
        assertEquals(ShipNavStatus.DOCKED, after.nav.status)
        val paid = credits - rig.verbs.agent().credits
        assertTrue(paid in 1..200, "46 units is one market unit at about 72: paid $paid")
    }

    @Test
    fun `flight mode is set once and drift costs one fuel`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        val fuel = rig.verbs.ship(ship).fuel.current
        rig.verbs.navigateTo(ship, Fixtures.ORE_MARKET, FlightMode.DRIFT)
        assertEquals(fuel - 1, rig.verbs.ship(ship).fuel.current)
        assertEquals(FlightMode.DRIFT, rig.verbs.ship(ship).nav.flightMode)
    }

    @Test
    fun `buying a ship needs a ship at the yard and takes the listed price`() = bothWays { rig ->
        rig.verbs.refreshShipyard(Fixtures.DRONE_SHIPYARD) // the probe is docked there
        val yard = rig.verbs.snapshot().shipyards.getValue(Fixtures.DRONE_SHIPYARD)
        val price = yard.priceOf(model.ship.ShipType.SHIP_MINING_DRONE)!!
        val credits = rig.verbs.agent().credits
        val drone = rig.verbs.purchaseShip(model.ship.ShipType.SHIP_MINING_DRONE, Fixtures.DRONE_SHIPYARD)
        assertEquals(credits - price, rig.verbs.agent().credits)
        assertTrue(drone.canMine)
        assertEquals(15, drone.cargo.capacity)
        assertEquals(Fixtures.DRONE_SHIPYARD, drone.nav.waypointSymbol)
    }

    @Test
    fun `purchase pays the market's price in volume batches and puts the goods in the hold`() = bothWays { rig ->
        val ship = Fixtures.COMMAND_SHIP
        rig.verbs.navigateTo(ship, Fixtures.ORE_MARKET)
        val market = rig.verbs.refreshMarket(Fixtures.ORE_MARKET)
        val iron = market.good(TradeSymbol.IRON)!!
        val credits = rig.verbs.agent().credits
        val bought = rig.verbs.purchase(ship, TradeSymbol.IRON, 40)
        assertEquals(40, bought.units)
        assertEquals((40 + iron.tradeVolume - 1) / iron.tradeVolume, bought.transactions.size)
        assertEquals(40, rig.verbs.ship(ship).cargo.unitsOf(TradeSymbol.IRON))
        assertEquals(credits - bought.credits, rig.verbs.agent().credits)
        assertTrue(bought.credits >= 40L * iron.purchasePrice, "prices only rise as we buy")
        assertFailsWith<VerbFailure.MarketRefuses> { rig.verbs.purchase(ship, TradeSymbol.GOLD_ORE, 1) }
    }
}

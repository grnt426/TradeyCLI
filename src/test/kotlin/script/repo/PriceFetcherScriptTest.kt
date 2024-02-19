package script.repo

import BaseTest
import createMarket
import createShip
import data.SavedScripts
import kotlinx.coroutines.runBlocking
import model.GameState
import model.market.Market
import model.ship.Ship
import model.ship.ShipNavStatus
import model.ship.toOrbit
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.TestInstance
import script.repo.PriceFetcherScript.PriceFetcherState.*
import java.lang.Thread.sleep
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PriceFetcherScriptTest : BaseTest() {

    lateinit var ship: Ship
    lateinit var script: PriceFetcherScript
    lateinit var market: Market

    @BeforeTest
    fun beforeTest() {
        transaction { SavedScripts.deleteAll() }
        ship = createShip()
        market = createMarket()
        script = PriceFetcherScript(ship)
        script.execDelayMs = 10
    }

    @Test
    fun `test still expired works`() {
        script.market = market
        market.lastRead.plusSeconds(60)
        assertFalse(script.stillExpired())
        market.lastRead = Instant.now().minusSeconds(16 * 60)
        assert(script.stillExpired())
    }

    @Test
    fun `test init transits to get price`() = runBlocking {
        toOrbit(ship)
        script.execute()
        sleep(5)
        assertEquals(GET_PRICE, script.currentState)
    }

    @Test
    fun `test await assign stays in assign for multiple execs`() = runBlocking {
        script.currentState = AWAIT_ASSIGNMENT
        script.execute()
        sleep(100)
        assertEquals(AWAIT_ASSIGNMENT, script.currentState)
    }

    @Test
    fun `test await assign when navigating changes to nav`() = runBlocking {
        ship.nav.status = ShipNavStatus.IN_TRANSIT
        ship.nav.route.arrival = Instant.now().plusSeconds(30_000)
        script.currentState = AWAIT_ASSIGNMENT
        script.execute()
        sleep(5)
        assertEquals(NAV, script.currentState)
    }

    @Test
    fun `change to assign await nav when given market from await assign`() = runBlocking {
        script.currentState = AWAIT_ASSIGNMENT
        script.setTarget(market)
        script.execute()
        sleep(100)
    }

    /**
     * Assigned Await Nav
     */

    @Test
    fun `assigned await nav changes to nav if navigating`() = runBlocking {
        script.currentState = ASSIGNED_AWAIT_NAV
        ship.nav.status = ShipNavStatus.IN_TRANSIT
        ship.nav.route.arrival = Instant.now().plusSeconds(60)
        script.execute()
        sleep(50)
        assertEquals(NAV, script.currentState)
    }

    @Test
    fun `assigned await nav changes to await assign if market null`() = runBlocking {
        script.currentState = ASSIGNED_AWAIT_NAV
        script.execute()
        sleep(50)
        assertEquals(AWAIT_ASSIGNMENT, script.currentState)
    }

    @Test
    fun `if already at market assigned then change to dock`() = runBlocking {
        script.currentState = ASSIGNED_AWAIT_NAV
        ship.nav.waypointSymbol = "here"
        market.symbol = "here"
        script.market = market

        script.execute()
        sleep(5)

        assertEquals(DOCK, script.currentState)
    }

    @Test
    fun `assign await nav changes to nav if all clear`() = runBlocking {
        script.currentState = ASSIGNED_AWAIT_NAV
        script.market = market
        ship.nav.status = ShipNavStatus.IN_ORBIT

        script.execute()
        sleep(10)

        assertEquals(AWAIT_NAV_RESP, script.currentState)
    }

    /**
     * Await Nav Resp
     */

    @Test
    fun `await nav resp causes error changes back to nav`() = runBlocking {
        script.currentState = AWAIT_NAV_RESP
        script.market = market
        ship.nav.status = ShipNavStatus.IN_ORBIT

        script.execute()
        sleep(20)
        script.failNavCb(null, Exception("Testing"))

        assertEquals(ASSIGNED_AWAIT_NAV, script.currentState)
    }

    @Test
    fun `await nav resp but is docked changes to get price`() = runBlocking {
        script.currentState = AWAIT_NAV_RESP
        ship.nav.status = ShipNavStatus.DOCKED

        script.execute()
        sleep(20)

        assertEquals(GET_PRICE, script.currentState)
    }

    @Test
    fun `ship is navigating while in await nav resp changes to nav`() = runBlocking {
        script.currentState = AWAIT_NAV_RESP
        ship.nav.status = ShipNavStatus.IN_ORBIT
        script.market = market

        script.execute()
        sleep(10)
        ship.nav.status = ShipNavStatus.IN_TRANSIT
        ship.nav.route.arrival = Instant.now().plusSeconds(60)
        sleep(50)

        assertEquals(NAV, script.currentState)
    }

    /**
     * Nav
     */

    @Test
    fun `in nav but no market will get new market and stay in nav`() = runBlocking {
        script.currentState = NAV
        ship.nav.route.destination.symbol = "destination"
        ship.nav.status = ShipNavStatus.IN_TRANSIT
        ship.nav.route.arrival = Instant.now().plusSeconds(60)
        market.symbol = "destination"
        GameState.markets = mutableMapOf("destination" to market)

        script.execute()
        sleep(50)

        assertEquals(NAV, script.currentState)
    }

    @Test
    fun `in nav but no market will fail to get new market and change to error`() = runBlocking {
        script.currentState = NAV
        ship.nav.route.destination.symbol = "destination"
        ship.nav.status = ShipNavStatus.IN_TRANSIT
        ship.nav.route.arrival = Instant.now().plusSeconds(60)
        GameState.markets = mutableMapOf()

        script.execute()
        sleep(50)

        assertEquals(ERROR, script.currentState)
    }

    @Test
    fun `in nav but docked will change to get price`() = runBlocking {
        script.currentState = NAV
        ship.nav.route.destination.symbol = "destination"
        ship.nav.status = ShipNavStatus.DOCKED
        script.market = market

        script.execute()
        sleep(5)

        assertEquals(GET_PRICE, script.currentState)
    }

    @Test
    fun `in nav and arrival far in future stays in nav`() = runBlocking {
        script.currentState = NAV
        ship.nav.route.destination.symbol = "destination"
        ship.nav.status = ShipNavStatus.IN_TRANSIT
        ship.nav.route.arrival = Instant.now().plusSeconds(60)
        script.market = market

        script.execute()
        sleep(100)

        assertEquals(NAV, script.currentState)
    }

    @Test
    fun `in nav and arrival passes, then change to dock`() = runBlocking {
        script.currentState = NAV
        ship.nav.route.destination.symbol = "destination"
        ship.nav.status = ShipNavStatus.IN_TRANSIT
        ship.nav.route.arrival = Instant.now().plusMillis(20)
        script.market = market

        script.execute()
        sleep(25)

        assertEquals(DOCK, script.currentState)
    }

    @Test
    fun `in nav but arrival already passed, change to dock`() = runBlocking {
        script.currentState = NAV
        ship.nav.route.destination.symbol = "destination"
        ship.nav.status = ShipNavStatus.IN_ORBIT
        ship.nav.route.arrival = Instant.now().minusSeconds(60)
        script.market = market

        script.execute()
        sleep(5)

        assertEquals(DOCK, script.currentState)
    }

    /**
     * Dock
     */

    @Test
    fun `in dock but navigating changes to nav`() = runBlocking {
        script.currentState = DOCK
        ship.nav.route.destination.symbol = "destination"
        ship.nav.status = ShipNavStatus.IN_TRANSIT
        ship.nav.route.arrival = Instant.now().plusSeconds(60)
        script.market = market

        script.execute()
        sleep(5)

        assertEquals(NAV, script.currentState)
    }

    @Test
    fun `in dock and orbiting, market is null changes to await assign`() = runBlocking {
        script.currentState = DOCK
        ship.nav.status = ShipNavStatus.IN_ORBIT

        script.execute()
        sleep(5)

        assertEquals(AWAIT_ASSIGNMENT, script.currentState)
    }
}
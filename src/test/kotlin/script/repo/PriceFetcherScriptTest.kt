package script.repo

import createMarket
import createShip
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import kotlinx.coroutines.runBlocking
import model.market.Market
import model.ship.*
import notification.NotificationManager
import screen.TextAnimationContainer
import script.repo.PriceFetcherScript.PriceFetcherState.*
import java.lang.Thread.sleep
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PriceFetcherScriptTest {

    lateinit var ship: Ship
    lateinit var script: PriceFetcherScript
    lateinit var market: Market

    @BeforeTest
    fun beforeTest() {
        ship = createShip()
        market = createMarket()
        script = PriceFetcherScript(ship)
        script.execDelayMs = 10
        mockkObject(TextAnimationContainer)
        mockkObject(NotificationManager)
        every { NotificationManager.createNotification(any(), any()) } returns Unit
        every { NotificationManager.createErrorNotification(any(), any()) } returns Unit
        mockkStatic(::navigateTo)
        every { navigateTo(any(), any(), any(), any()) } returns Unit
        mockkStatic(::toOrbit)
        every { toOrbit(any(), any(), any()) } returns true
        mockkStatic(::toDock)
        every { toDock(any(), any(), any()) } returns true
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
        sleep(50)
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
        sleep(100)
        assertEquals(NAV, script.currentState)
    }

    @Test
    fun `change to assign await nav when given market from await assign`() = runBlocking {
        script.currentState = AWAIT_ASSIGNMENT
        script.setTarget(market)
        script.execute()
        sleep(100)
    }

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
}
package script.repo

import BaseTest
import createShip
import data.SavedScripts
import io.ktor.client.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import kotlinx.coroutines.runBlocking
import model.actions.Extraction
import model.actions.Yield
import model.market.TradeSymbol
import model.responsebody.ExtractionResponse
import model.ship.Cooldown
import model.ship.Ship
import model.ship.components.Cargo
import model.ship.components.Inventory
import model.ship.jettisonCargo
import notification.NotificationManager
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.TestInstance
import screen.TextAnimationContainer
import script.repo.BasicMiningScript.MiningStates.*
import java.lang.Thread.sleep
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasicMiningScriptTest : BaseTest() {

    private val client = mockk<HttpClient>()
    lateinit var ship: Ship
    lateinit var bms: BasicMiningScript

    @BeforeTest
    fun beforeTest() {
        transaction { SavedScripts.deleteAll() }
        mockkObject(TextAnimationContainer)
        mockkObject(NotificationManager)
        every { jettisonCargo(any(), any<Inventory>()) } returns Unit
        ship = createShip()
        bms = BasicMiningScript(ship)
        bms.execDelayMs = 10
    }

    /**
     * Mining
     */
    @Test
    fun `start in mining but cargo full changes to full await pickup`() = runBlocking {
        ship.cargo.units = ship.cargo.capacity
        bms.execute()
        sleep(5)
        assertEquals(FULL_AWAITING_PICKUP, bms.currentState)
        transaction {
            val res = SavedScripts.selectAll().where(SavedScripts.id eq bms.uuid).single()
            assertNotNull(res)
            assertEquals("FULL_AWAITING_PICKUP", res[SavedScripts.scriptState])
            assertEquals("Symbol-0", res[SavedScripts.entityId])
            assertEquals("BasicMiningScript", res[SavedScripts.scriptType])
        }
    }

    @Test
    fun `start in mining with cooldown still applied changes to mining cooldown`() = runBlocking {
        ship.cooldown.remainingSeconds = 10
        ship.cooldown.expiration = Instant.now().plusSeconds(10)
        bms.execute()
        sleep(5)
        assertEquals(MINING_COOLDOWN, bms.currentState)
    }

    @Test
    fun `start in mining and changes to await mining response`() = runBlocking {
        bms.execute()
        sleep(100)
        assertEquals(AWAIT_MINING_RESPONSE, bms.currentState)
    }

    /**
     * Await mining response
     */

    @Test
    fun `await mining response will not change if extractResult stays null`() = runBlocking {
        bms.currentState = AWAIT_MINING_RESPONSE
        bms.execute()
        sleep(150)
        assertEquals(AWAIT_MINING_RESPONSE, bms.currentState)
    }

    @Test
    fun `await mining response will change to keep valuables when extractResult is provided`() = runBlocking {
        bms.currentState = AWAIT_MINING_RESPONSE
        bms.execute()
        sleep(150)
        assertEquals(AWAIT_MINING_RESPONSE, bms.currentState)
        bms.mineCallback(buildExtractionResponse(ship))
        sleep(10)
        assertEquals(KEEP_VALUABLES, bms.currentState)
    }

    @Test
    fun `await mining response will change to mining when extractResult fails`() = runBlocking {
        bms.currentState = AWAIT_MINING_RESPONSE
        bms.execute()
        sleep(50)
        assertEquals(AWAIT_MINING_RESPONSE, bms.currentState)
        bms.failback(null, Exception("Failed"))
        sleep(10)
        assertEquals(MINING, bms.currentState)
    }

    /**
     * Keep valuables
     */

    @Test
    fun `keep valuables jettisons nothing when cargo empty and changes to mining cooldown`() = runBlocking {
        bms.currentState = KEEP_VALUABLES
        ship.cooldown.remainingSeconds = 10
        bms.execute()
        sleep(5)
        assertEquals(MINING_COOLDOWN, bms.currentState)
    }

    @Test
    fun `keep valuables jettisons 1 ice water to empty cargo and changes to mining cooldown`() = runBlocking {
        bms.currentState = KEEP_VALUABLES
        ship.cooldown.remainingSeconds = 10
        ship.cargo.units = 1
        ship.cargo.inventory.add(Inventory(TradeSymbol.ICE_WATER, "ice", "ice", 1))
        bms.execute()
        sleep(5)
        assertEquals(0, ship.cargo.units)
        assertEquals(0, ship.cargo.inventory.size)
        assertEquals(MINING_COOLDOWN, bms.currentState)
    }

    @Test
    fun `keep valuables jettisons 1 ice water to cargo of 1 iron ore and changes to mining cooldown`() = runBlocking {
        bms.currentState = KEEP_VALUABLES
        ship.cooldown.remainingSeconds = 10
        ship.cargo.units = 2
        with(ship.cargo.inventory) {
            add(Inventory(TradeSymbol.ICE_WATER, "ice", "ice", 1))
            add(Inventory(TradeSymbol.IRON_ORE, "iron_ore", "iron ore", 1))
        }
        bms.execute()
        sleep(5)
        assertEquals(1, ship.cargo.units)
        assertEquals(1, ship.cargo.inventory.size)
        assertEquals(TradeSymbol.IRON_ORE, ship.cargo.inventory[0].symbol)
        assertEquals(MINING_COOLDOWN, bms.currentState)
    }

    @Test
    fun `keep valuables is full and changes to await pick up`() = runBlocking {
        bms.currentState = KEEP_VALUABLES
        ship.cargo.units = ship.cargo.capacity
        bms.execute()
        sleep(5)
        assertEquals(FULL_AWAITING_PICKUP, bms.currentState)
    }

    /**
     * Full Awaiting Pickup
     */

    @Test
    fun `full awaiting pickup changes to mining cooldown with empty inventory`() = runBlocking {
        bms.currentState = FULL_AWAITING_PICKUP
        ship.cargo.units = 0
        ship.cooldown.remainingSeconds = 60
        bms.execute()
        sleep(5)
        assertEquals(MINING_COOLDOWN, bms.currentState)
    }

    @Test
    fun `full awaiting pickup changes to mining cooldown after time until cargo reduced to not quite empty`() =
        runBlocking {
            bms.currentState = FULL_AWAITING_PICKUP
            ship.cargo.units = ship.cargo.capacity
            ship.cooldown.remainingSeconds = 60
            bms.execute()
            sleep(50)
            assertEquals(FULL_AWAITING_PICKUP, bms.currentState)
            ship.cargo.units -= 1
            sleep(50)
            assertEquals(MINING_COOLDOWN, bms.currentState)
        }

    private fun buildExtractionResponse(ship: Ship): ExtractionResponse {
        return ExtractionResponse(
            Extraction(
                ship.symbol, Yield(TradeSymbol.ICE_WATER, 1),
            ),
            Cooldown(
                ship.symbol,
                1,
                2
            ),
            Cargo(
                ship.cargo.capacity,
                1,
                mutableListOf( Inventory(TradeSymbol.ICE_WATER, TradeSymbol.ICE_WATER.name, "water", 1))
            )
        )
    }
}
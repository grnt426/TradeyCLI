package script.repo

import client.SpaceTradersClient
import data.DbClient
import data.SavedScripts
import io.ktor.client.*
import io.mockk.mockk
import io.mockk.mockkObject
import model.actions.Extraction
import model.actions.Yield
import model.market.TradeSymbol
import model.responsebody.ExtractionResponse
import model.ship.Cooldown
import model.ship.Ship
import model.ship.components.Cargo
import model.ship.components.Inventory
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasicMiningScriptTest {

    private val client = mockk<HttpClient>()

    @BeforeAll
    fun setup() {
        DbClient.createClient("unittests")
        mockkObject(SpaceTradersClient)
    }

    @BeforeEach
    fun beforeTest() {
        transaction { SavedScripts.deleteAll() }
    }

//    @Test
//    fun `testing the basic mining script`() = runBlocking {
//        val ship = createShip()
//        val bms = BasicMiningScript(ship)
//        every { SpaceTradersClient.enqueueRequest<ExtractionResponse>(any(), any(), any()) }
//            .apply { bms.mineCallback(buildExtractionResponse(ship)) }
//        launch{bms.execute()}.join()
//        sleep(5000)
//        assertEquals(1, ship.cargo.units)
//        assertEquals(BasicMiningScript.MiningStates.FULL_AWAITING_PICKUP, bms.currentState)
//        transaction {
//            val res = SavedScripts.selectAll().where(SavedScripts.id eq bms.uuid).single()
//            assertNotNull(res)
//            assertEquals("FULL_AWAITING_PICKUP", res[SavedScripts.scriptState])
//            assertEquals("Symbol-0", res[SavedScripts.entityId])
//            assertEquals("BasicMiningScript", res[SavedScripts.scriptType])
//        }
//    }

//    @Test
//    fun `large basic script test`() = runBlocking {
//        val jobs = mutableListOf<Job>()
//        (0..< 10_000).forEach { i ->
//            val ship = createShip(i.toString())
//            val bms = BasicMiningScript(ship)
//            jobs.add(launch{bms.execute()})
//        }
//
//        jobs.forEach { it.join() }
//        jobs.clear()
//
//        // wait for them to execute and stop
//        sleep(5_000)
//
//        transaction {
//            val res = SavedScripts.selectAll().where {
//                SavedScripts.scriptState eq BasicMiningScript.MiningStates.FULL_AWAITING_PICKUP.toString()
//            }
//            assertNotNull(res)
//            assertEquals(10_000, res.count())
//        }
//        assertEquals(0, DbClient.writeQueue.size)
//    }

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
package script.repo

import data.DbClient
import data.SavedScripts
import kotlinx.coroutines.Job
import model.Location
import model.ship.*
import model.ship.components.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.lang.Thread.sleep
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BasicMiningScriptTest {

    @BeforeAll
    fun setup() {
        DbClient.createClient("unittests")
    }

    @BeforeEach
    fun beforeTest() {
        transaction { SavedScripts.deleteAll() }
    }

    @Test
    fun `testing the basic mining script`() = runBlocking {
        val ship = createShip()
        val bms = BasicMiningScript(ship)
        launch{bms.execute()}.join()
        sleep(5000)
        assertEquals(100, ship.cargo.units)
        assertEquals(BasicMiningScript.MiningStates.FULL_AWAITING_PICKUP, bms.currentState)
        transaction {
            val res = SavedScripts.selectAll().where(SavedScripts.id eq bms.uuid).single()
            assertNotNull(res)
            assertEquals("FULL_AWAITING_PICKUP", res[SavedScripts.scriptState])
            assertEquals("Symbol-0", res[SavedScripts.entityId])
            assertEquals("BasicMiningScript", res[SavedScripts.scriptType])
        }
    }

    @Test
    fun `large basic script test`() = runBlocking {
        val jobs = mutableListOf<Job>()
        (0..< 10_000).forEach { i ->
            val ship = createShip(i.toString())
            val bms = BasicMiningScript(ship)
            jobs.add(launch{bms.execute()})
        }

        jobs.forEach { it.join() }
        jobs.clear()

        // wait for them to execute and stop
        sleep(5_000)

        transaction {
            val res = SavedScripts.selectAll().where {
                SavedScripts.scriptState eq BasicMiningScript.MiningStates.FULL_AWAITING_PICKUP.toString()
            }
            assertNotNull(res)
            assertEquals(10_000, res.count())
        }
        assertEquals(0, DbClient.writeQueue.size)
    }

    private fun createShip(symbol: String = "0"): Ship {
        return Ship(
            "Symbol-$symbol",
            Navigation(
                "SystemSymbol",
                "WaypointSymbol",
                Route(
                    createLocation(),
                    createLocation(),
                    createLocation(),
                    "",
                    ""
                ),
                "Status",
                "FlightMode"
            ),
            Crew(0L, 0L, 0L, "", 0L, 0L),
            Fuel(0L, 0L, FuelConsumed(0L, "")),
            Cooldown("", 0L, 0L),
            Frame("", "", "", 0L, 0L, 0L, 0L,
                Requirements()
            ),
            Engine("", "", "", 0L, 0L, Requirements()),
            Reactor("", "", "", 0L, 0L, Requirements()),
            emptyList(),
            emptyList(),
            Registration("", "", ""),
            Cargo(100, 0, emptyList())
        )

    }

    private fun createLocation(): Location {
        return Location(
            "",
            "",
            "",
            0L,
            0L
        )
    }
}
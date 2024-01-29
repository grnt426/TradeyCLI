package script.repo

import data.Location
import data.ship.*
import data.ship.components.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class BasicMiningScriptTest {

    @Test
    fun `testing the basic mining script`() {
        val ship = createShip()
        val bms = BasicMiningScript(ship)
        bms.execute()
        assertEquals(1000, ship.cargo.units)
    }

    private fun createShip(): Ship {
        return Ship(
            "Symbol",
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
package engine

import model.ship.FlightMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** The wiki's formulas, checked against its own worked numbers. */
class TravelTest {

    @Test
    fun `cruise fuel is the rounded distance with a floor of one`() {
        assertEquals(1, Travel.fuelCost(0.0, FlightMode.CRUISE))
        assertEquals(50, Travel.fuelCost(50.4, FlightMode.CRUISE))
        assertEquals(51, Travel.fuelCost(50.5, FlightMode.CRUISE))
        assertEquals(1, Travel.fuelCost(300.0, FlightMode.DRIFT))
        assertEquals(600, Travel.fuelCost(300.0, FlightMode.BURN))
        assertEquals(2, Travel.fuelCost(0.0, FlightMode.BURN))
    }

    @Test
    fun `travel time follows round(d * multiplier over speed + 15)`() {
        // 300 units at speed 30 on cruise: 300 * 25 / 30 + 15 = 265
        assertEquals(265, Travel.seconds(300.0, FlightMode.CRUISE, 30))
        // zero distance rounds to one unit
        assertEquals(16, Travel.seconds(0.0, FlightMode.CRUISE, 30))
        // drift is ten times slower
        assertEquals(2515, Travel.seconds(300.0, FlightMode.DRIFT, 30))
        // burn is twice as fast
        assertEquals(140, Travel.seconds(300.0, FlightMode.BURN, 30))
    }

    @Test
    fun `distance is euclidean`() {
        assertEquals(5.0, Travel.distance(0, 0, 3, 4))
    }
}

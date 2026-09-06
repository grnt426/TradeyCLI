package engine

import model.system.Waypoint
import model.system.WaypointType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Routing through fuel stops instead of drifting. */
class FuelRouteTest {
    private fun wp(symbol: String, x: Int, y: Int) = Waypoint(symbol = symbol, type = WaypointType.PLANET, systemSymbol = "X1-T", x = x, y = y)

    @Test
    fun `a leg longer than the tank goes through the stops that make it, shortest chain first`() {
        val far = wp("X1-T-J57", 400, 0)
        val home = wp("X1-T-H50", 0, 0)
        val a = wp("X1-T-A", 150, 10)
        val b = wp("X1-T-B", 280, -10)
        val c = wp("X1-T-C", 200, 300)
        // A 300 tank cannot cover 400 direct; via B (120) then home (280) is two hops; via A then home is 150 + 150 and shorter overall.
        val route = FuelRoute.plan(far, home, listOf(a, b, c), capacity = 300, fuelNow = 299, canFillHere = true)
        assertEquals(listOf("X1-T-A"), route?.map { it.symbol })
    }

    @Test
    fun `a direct leg within the tank needs no stops, and no chain at all means null`() {
        val here = wp("X1-T-P", 0, 0)
        val there = wp("X1-T-Q", 100, 0)
        assertEquals(emptyList(), FuelRoute.plan(here, there, emptyList(), capacity = 300, fuelNow = 150, canFillHere = false))
        val unreachable = wp("X1-T-R", 900, 0)
        assertNull(FuelRoute.plan(here, unreachable, listOf(wp("X1-T-S", 200, 0)), capacity = 300, fuelNow = 300, canFillHere = true))
    }

    @Test
    fun `with no fuel to be had here the first hop is limited to what is in the tank`() {
        val rock = wp("X1-T-ROCK", 0, 0)
        val near = wp("X1-T-N", 60, 0)
        val market = wp("X1-T-M", 250, 0)
        // 80 fuel on a rock: the 250 leg is out, but N at 60 is in, and from N a full tank reaches M.
        assertEquals(listOf("X1-T-N"), FuelRoute.plan(rock, market, listOf(near), capacity = 300, fuelNow = 80, canFillHere = false)?.map { it.symbol })
    }
}

package engine

import model.ship.FlightMode
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * The travel arithmetic from api-docs/wiki/Travel-Fuel-and-Time.md. Used by the verbs (to refuse
 * a trip the tank cannot cover), by the decisions (to cost a route) and by the simulator (to
 * model the trip), so all three agree.
 */
object Travel {
    fun distance(x1: Int, y1: Int, x2: Int, y2: Int): Double = hypot((x1 - x2).toDouble(), (y1 - y2).toDouble())

    /** Fuel a trip of [distance] costs in [mode]. Minimum one, or two on BURN. */
    fun fuelCost(distance: Double, mode: FlightMode): Long {
        val d = distance.roundToLong()
        return when (mode) {
            FlightMode.CRUISE, FlightMode.STEALTH -> max(1, d)
            FlightMode.DRIFT -> 1
            FlightMode.BURN -> max(2, 2 * d)
        }
    }

    /** Seconds a trip of [distance] takes at engine [speed] in [mode]. */
    fun seconds(distance: Double, mode: FlightMode, speed: Long): Long {
        val d = max(1.0, distance).roundToLong()
        val multiplier = when (mode) {
            FlightMode.CRUISE -> 25.0
            FlightMode.DRIFT -> 250.0
            FlightMode.BURN -> 12.5
            FlightMode.STEALTH -> 30.0
        }
        return (d * multiplier / speed + 15).roundToLong()
    }
}

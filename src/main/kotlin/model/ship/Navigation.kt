package model.ship

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Navigation(
    val systemSymbol: String,
    val waypointSymbol: String,
    val route: Route,
    val status: ShipNavStatus,
    val flightMode: FlightMode = FlightMode.CRUISE,
) {
    /** True while the ship is between waypoints and the arrival is still ahead of [now]. */
    fun inTransitAt(now: Instant): Boolean = status == ShipNavStatus.IN_TRANSIT && route.arrival.isAfter(now)
}

/** The ship's speed setting; see api-docs/wiki/Travel-Fuel-and-Time.md for what each costs. */
@Serializable
enum class FlightMode { DRIFT, STEALTH, CRUISE, BURN }

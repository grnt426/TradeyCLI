package script.actions

import data.ship.Ship
import data.system.Waypoint
import script.StateScope

fun StateScope.navTo(ship: Ship, waypoint: Waypoint) {
    println("Navigating to")
}
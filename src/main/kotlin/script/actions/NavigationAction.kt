package script.actions

import model.ship.Ship
import model.system.Waypoint
import script.StateScope

fun StateScope.navTo(ship: Ship, waypoint: Waypoint) {
    println("Navigating to")
}
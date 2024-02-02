package script.actions

import data.ship.Ship
import script.StateScope
import java.time.LocalDateTime

fun StateScope.mine(ship: Ship) {
    println("Mining....")
    ship.cargo.units += 25
    LocalDateTime.now().plusHours(1)
}
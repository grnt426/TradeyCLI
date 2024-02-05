package script.actions

import model.ship.Ship
import script.StateScope
import java.time.LocalDateTime

fun StateScope.mine(ship: Ship) {
    println("Mining....")

    // delay until available after nav/off cooldown from previous mining

    ship.cargo.units += 25
    LocalDateTime.now().plusHours(1)
}
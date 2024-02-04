package script.actions

import data.ship.Ship
import kotlinx.coroutines.delay
import script.StateScope
import java.time.LocalDateTime

fun StateScope.mine(ship: Ship) {
    println("Mining....")

    // delay until available after nav/off cooldown from previous mining

    ship.cargo.units += 25
    LocalDateTime.now().plusHours(1)
}
package script.actions

import data.ship.Ship
import script.ScriptAction
import script.State
import script.StateScope
import java.time.LocalDateTime

class MiningAction: ScriptAction() {
    override fun doAction(): LocalDateTime {
        return LocalDateTime.now().plusHours(1)
    }
}

fun mine(ship: Ship) {
    println("Mining....")
    ship.cargo.units += 25
    LocalDateTime.now().plusHours(1)
}
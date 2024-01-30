package script.actions

import data.ship.Ship
import script.ScriptAction
import script.ScriptScope
import script.StateScope
import java.time.LocalDateTime

class MiningAction: ScriptAction() {
    override fun run(): LocalDateTime {
        return LocalDateTime.now().plusHours(1)
    }
}

fun StateScope.mine(ship: Ship) {
    enqueueAction {
        println("Mining....")
        ship.cargo.units += 100
        LocalDateTime.now().plusHours(1)
    }
}
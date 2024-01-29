package script.actions

import script.ScriptAction
import script.ScriptScope
import java.time.LocalDateTime

class MiningAction: ScriptAction() {
    override fun run(): LocalDateTime {
        return LocalDateTime.now().plusHours(1)
    }
}

fun ScriptScope.mine() {
    println("Mining....")
    ship.cargo.units += 1000
}
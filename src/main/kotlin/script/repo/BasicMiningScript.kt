package script.repo

import data.ship.Ship
import script.actions.mine
import script.actions.noop
import script.script

class BasicMiningScript(val ship: Ship) {

    fun execute() {
        println("Executing...")
        script {
            println("Inside script")
            state(cond = { ship.cargo.units < ship.cargo.capacity } ) {
                println("Inside state")
                mine(ship)
            }
        }.run()
    }
}

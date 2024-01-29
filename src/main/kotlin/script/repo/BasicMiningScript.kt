package script.repo

import data.ship.Navigation
import data.ship.Route
import data.ship.Ship
import script.ScriptScope
import script.State
import script.actions.mine
import script.actions.noop
import script.script

class BasicMiningScript(val ship: Ship) {

    fun execute() {
        script(ship) {
            state(cond = { ship.cargo.units < ship.cargo.capacity } ) {
                mine()
            }
            state(cond = { ship.cargo.units >= ship.cargo.capacity } ) {
                noop()
            }
        }.run()
    }
}

package script.repo

import data.ship.Ship
import script.State
import script.ScriptExecutor
import script.script
import script.script
import script.actions.mine
import script.actions.noop

class BasicMiningScript(val ship: Ship): ScriptExecutor<BasicMiningScript.MiningStates>(MiningStates.MINING) {

    enum class MiningStates {
        MINING,
        STOP
    }

    override fun execute() {
        script {
            println("Start!")
            state(cond = { ship.cargo.units < ship.cargo.capacity} ) {
                mine(ship)
                println("I have ${ship.cargo.units} in the cargo bay")
            }
            state(cond = { ship.cargo.units >= ship.cargo.capacity} ) {
                noop()
                println("${ship.cargo.units} of ${ship.cargo.capacity} used;" +
                        "Full? ${ship.cargo.units >= ship.cargo.capacity}")
                println("My cargo bay is full!")
            }
        }.runForever()
    }
}

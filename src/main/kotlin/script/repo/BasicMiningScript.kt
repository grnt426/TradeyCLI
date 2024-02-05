package script.repo

import model.ship.Ship
import model.ship.components.cargoFull
import model.ship.components.cargoNotFull
import script.ScriptExecutor
import script.script
import script.actions.mine
import script.actions.noop

class BasicMiningScript(val ship: Ship): ScriptExecutor<BasicMiningScript.MiningStates>(
    MiningStates.MINING, "BasicMiningScript", ship.symbol
) {

    enum class MiningStates {
        MINING,
        STOP,
        KEEP_VALUABLES,
        FULL_AWAITING_PICKUP
    }

    override fun execute() {
        script {
            println("Start!")
            state(matchesState(MiningStates.MINING)) {
                println("Mining action")
                mine(ship)
                println("I have ${ship.cargo.units} in the cargo bay")
                changeState(MiningStates.KEEP_VALUABLES)
            }

            state(matchesState(MiningStates.KEEP_VALUABLES)) {
                println("Jettisoned junk")
                // jettison less valuable stuff

                if (cargoFull(ship)) {
                    changeState(MiningStates.FULL_AWAITING_PICKUP)
                }
                else {
                    println("Back to mining state")
                    changeState(MiningStates.MINING)
                }
            }

            state(matchesState(MiningStates.FULL_AWAITING_PICKUP)){
                println("Awaiting pickup")
                // once freighter arrives, dump goods

                if (cargoNotFull(ship)) {
                    changeState(MiningStates.MINING)
                }
            }

            state(matchesState(MiningStates.STOP)) {
                println("Stopped")
                noop()
                println("${ship.cargo.units} of ${ship.cargo.capacity} used;" +
                        " Full? ${ship.cargo.units >= ship.cargo.capacity}")
            }
        }.runForever(500)
    }
}

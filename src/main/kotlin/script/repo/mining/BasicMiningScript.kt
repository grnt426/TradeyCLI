package script.repo.mining

import model.market.TradeSymbol
import model.ship.Ship
import model.ship.cargoNotFull
import script.MessageableScriptExecutor
import script.actions.noop
import script.repo.mining.BasicMiningScript.MiningMessages
import script.repo.mining.BasicMiningScript.MiningStates
import script.repo.mining.BasicMiningScript.MiningStates.*
import script.repo.modules.MiningModule
import script.script


class BasicMiningScript(val ship: Ship): MessageableScriptExecutor<MiningStates, MiningMessages>(
    MINING, "BasicMiningScript", ship.symbol
) {

    init {
        ship.script = this
        execDelayMs = 1_000
    }
    enum class MiningStates {
        MINING,
        AWAIT_MINING_RESPONSE,
        MINING_COOLDOWN,
        STOP,
        KEEP_VALUABLES,
        FULL_AWAITING_PICKUP
    }

    enum class MiningMessages {
        HAULER_FINISHED,
    }

    private val ALWAYS_WORTHLESS_GOODS = listOf(TradeSymbol.ICE_WATER)

    override fun execute() {
        script {
            MiningModule(this@BasicMiningScript).addMiningModule(
                ship, MINING, MINING_COOLDOWN, AWAIT_MINING_RESPONSE, KEEP_VALUABLES,
                ALWAYS_WORTHLESS_GOODS, FULL_AWAITING_PICKUP, this
            )

            state(matchesState(FULL_AWAITING_PICKUP)){

                // Once our cargo isn't full anymore, resume mining
                if (cargoNotFull(ship)) {
                    changeState(MINING_COOLDOWN)
                }
            }

            state(matchesState(STOP)) {
//                println("Stopped")
                noop()
                stopScript()
//                println("${ship.cargo.units} of ${ship.cargo.capacity} used;" +
//                        " Full? ${ship.cargo.units >= ship.cargo.capacity}")
            }
        }.runForever(execDelayMs)
    }
}

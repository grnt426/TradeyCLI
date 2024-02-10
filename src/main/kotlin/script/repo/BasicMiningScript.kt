package script.repo

import io.ktor.client.statement.*
import io.ktor.http.*
import model.actions.Extract
import model.market.TradeSymbol
import model.ship.*
import script.MessageableScriptExecutor
import script.actions.mine
import script.actions.noop
import script.repo.BasicMiningScript.*
import script.repo.BasicMiningScript.MiningStates.*
import script.script
import java.time.Instant


class BasicMiningScript(val ship: Ship): MessageableScriptExecutor<MiningStates, MiningMessages>(
    MINING, "BasicMiningScript", ship.symbol
) {

    init {
        ship.script = this
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

    private var extractResult: Extract? = null
    private var failed = false
    var cooldownRemaining = 0L

    override fun execute() {
        script {
            state(matchesState(MINING_COOLDOWN)) {
                if (isCooldownExpired(ship.cooldown)) {
                    changeState(MINING)
                } else {
                    cooldownRemaining = ship.cooldown.expirationDateTime?.epochSecond?.minus(Instant.now().epochSecond)!!
                    ship.cooldown.remainingSeconds = cooldownRemaining
                }
            }

            state(matchesState(MINING)) {
                if (cargoFull(ship)) {
                    println("In mining state, but full; Going to await pickup")
                    changeState(FULL_AWAITING_PICKUP)
                }
                else {
                    extractResult = null
                    failed = false
                    println("Mining action")
                    mine(ship, ::mineCallback, ::failback)
                    changeState(AWAIT_MINING_RESPONSE)
                }
            }

            state(matchesState(AWAIT_MINING_RESPONSE)) {
                println("Awaiting mining response...")
                if (extractResult != null) {
                    applyExtractResults(ship, extractResult!!)
                    val yield = extractResult!!.extraction.yield
                    println("Mined ${yield.units} ${yield.symbol}")
                    changeState(KEEP_VALUABLES)
                }
                else if (failed) {
                    println("Mining failed! Retrying....")
                    failed = false
                    changeState(MINING_COOLDOWN)
                }
            }

            state(matchesState(KEEP_VALUABLES)) {
                println("Ejecting commons")

                // eject garbage
                jettisonCargo(ship, ALWAYS_WORTHLESS_GOODS)

                if (cargoFull(ship)) {
                    changeState(FULL_AWAITING_PICKUP)
                }
                else {
                    changeState(MINING_COOLDOWN)
                }
            }

            state(matchesState(FULL_AWAITING_PICKUP)){

                // Once our cargo isn't full anymore, resume mining
                if (cargoNotFull(ship)) {
                    changeState(MINING)
                }
            }

            state(matchesState(STOP)) {
//                println("Stopped")
                noop()
                stopScript()
//                println("${ship.cargo.units} of ${ship.cargo.capacity} used;" +
//                        " Full? ${ship.cargo.units >= ship.cargo.capacity}")
            }
        }.runForever(500)
    }

    private suspend fun mineCallback(res: Extract) {
        println("Extract response successful")
        extractResult = res
        failed = false
    }

    private suspend fun failback(resp: HttpResponse?, ex: Exception?) {
        println("Failback called")
        if (resp != null) {
            println(resp.bodyAsText())

            // it could also be traveling, should handle
            if (resp.status == HttpStatusCode.Conflict) {
                changeState(MINING_COOLDOWN)
            }
            else {
                failed = true
            }
        }
        else if (ex != null){
            println(ex.message)
            failed = true
        }
    }
}

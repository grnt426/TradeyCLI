package script.repo

import io.ktor.client.statement.*
import io.ktor.http.*
import model.actions.Extract
import model.ship.Ship
import model.ship.applyExtractResults
import model.ship.components.cargoFull
import model.ship.components.cargoNotFull
import model.ship.isCooldownExpired
import script.MessageableScriptExecutor
import script.ScriptExecutor
import script.actions.mine
import script.actions.noop
import script.repo.BasicMiningScript.*
import script.repo.BasicMiningScript.MiningMessages.*
import script.repo.BasicMiningScript.MiningStates.*
import script.script
import java.time.Instant


class BasicMiningScript(val ship: Ship): MessageableScriptExecutor<MiningStates, MiningMessages>(
    MINING, "BasicMiningScript", ship.symbol
) {

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

    private var extractResult: Extract? = null
    private var failed = false

    override fun execute() {
        script {
            state(matchesState(MINING_COOLDOWN)) {
                print("Cooling state: ")
                if (isCooldownExpired(ship.cooldown)) {
                    println("Cooldown complete")
                    changeState(MINING)
                } else {
                    print("Cooldown has ")
                    print((ship.cooldown.expirationDateTime?.epochSecond?.minus(Instant.now().epochSecond)))
                    println(" seconds remain....")
                }
            }

            state(matchesState(MINING)) {
                extractResult = null
                failed = false
                println("Mining action")
                mine(ship, ::mineCallback, ::failback)
                changeState(AWAIT_MINING_RESPONSE)
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
                ship.cargo = extractResult!!.cargo

                // eject garbage

                if (cargoFull(ship)) {
                    changeState(FULL_AWAITING_PICKUP)
                }
                else {
                    changeState(MINING_COOLDOWN)
                }
            }

            state(matchesState(FULL_AWAITING_PICKUP)){
                println("Awaiting pickup")

                // A hauler may have finished transferring cargo from us, but ran out of room to
                // take it all. Regardless, go back to mining
                if (cargoNotFull(ship) && consumeMessage(HAULER_FINISHED)) {
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

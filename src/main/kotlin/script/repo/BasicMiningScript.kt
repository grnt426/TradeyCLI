package script.repo

import io.ktor.client.statement.*
import io.ktor.http.*
import model.actions.Extract
import model.ship.Ship
import model.ship.components.cargoFull
import model.ship.components.cargoNotFull
import script.ScriptExecutor
import script.actions.mine
import script.actions.noop
import script.script
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.*


class BasicMiningScript(val ship: Ship): ScriptExecutor<BasicMiningScript.MiningStates>(
    MiningStates.MINING, "BasicMiningScript", ship.symbol
) {

    enum class MiningStates {
        MINING,
        AWAIT_MINING_RESPONSE,
        MINING_COOLDOWN,
        STOP,
        KEEP_VALUABLES,
        FULL_AWAITING_PICKUP
    }

    private var extractResult: Extract? = null
    private var failed = false

    override fun execute() {
        script {

            state(matchesState(MiningStates.MINING_COOLDOWN)) {
                print("Cooling state: ")
                if (ship.cooldown.expiration == null) {
                    println("Cooldown is null")
                }
                else {
                    val cooldownTime = Instant.parse(ship.cooldown.expiration)
                    val now = LocalDateTime.now(ZoneOffset.UTC)
                    if (
                        ship.cooldown.expiration != null &&
                        now.toInstant(ZoneOffset.UTC) >= cooldownTime
                    ) {
                        println("Cooldown complete")
                        changeState(MiningStates.MINING)
                    } else {
                        print("Cooldown has ")
                        print((cooldownTime.epochSecond - now.toEpochSecond(ZoneOffset.UTC)))
                        println(" seconds remain....")
                    }
                }
            }

            state(matchesState(MiningStates.MINING)) {
                extractResult = null
                failed = false
                println("Mining action")
                mine(ship, ::mineCallback, ::failback)
                changeState(MiningStates.AWAIT_MINING_RESPONSE)
            }

            state(matchesState(MiningStates.AWAIT_MINING_RESPONSE)) {
                println("Awaiting mining response...")
                if (extractResult != null) {
                    ship.cargo = extractResult!!.cargo
                    ship.cooldown = extractResult!!.cooldown
                    val yield = extractResult!!.extraction.yield
                    println("Mined ${yield.units} ${yield.symbol}")
                    changeState(MiningStates.KEEP_VALUABLES)
                }
                else if (failed) {
                    println("Mining failed! Retrying....")
                    failed = false
                    changeState(MiningStates.MINING_COOLDOWN)
                }
            }

            state(matchesState(MiningStates.KEEP_VALUABLES)) {
                println("Ejecting commons")
                ship.cargo = extractResult!!.cargo

                // eject garbage

                if (cargoFull(ship)) {
                    changeState(MiningStates.FULL_AWAITING_PICKUP)
                }
                else {
                    changeState(MiningStates.MINING_COOLDOWN)
                }
            }

            state(matchesState(MiningStates.FULL_AWAITING_PICKUP)){
                println("Awaiting pickup")

                if (cargoNotFull(ship)) {
                    changeState(MiningStates.MINING)
                }
            }

            state(matchesState(MiningStates.STOP)) {
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
                changeState(MiningStates.MINING_COOLDOWN)
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

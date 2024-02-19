package script.repo

import io.ktor.client.statement.*
import model.market.TradeSymbol
import model.responsebody.ExtractionResponse
import model.ship.*
import notification.NotificationManager
import script.MessageableScriptExecutor
import script.actions.mine
import script.actions.noop
import script.repo.BasicMiningScript.MiningMessages
import script.repo.BasicMiningScript.MiningStates
import script.repo.BasicMiningScript.MiningStates.*
import script.script
import java.time.Instant


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

    private var extractResult: ExtractionResponse? = null
    var cooldownRemaining = 0L

    override fun execute() {
        script {
            state(matchesState(MINING_COOLDOWN)) {
                println("mining cooldown")
                if (isCooldownExpired(ship.cooldown)) {
                    changeState(MINING)
                } else {
                    val whenExpiresSeconds = ship.cooldown.expiration?.epochSecond ?: 0
                    cooldownRemaining = whenExpiresSeconds.minus(Instant.now().epochSecond)
                    ship.cooldown.remainingSeconds = cooldownRemaining
                }
            }

            state(matchesState(MINING)) {
                if (cargoFull(ship)) {
                    println("In mining state, but full; Going to await pickup")
                    changeState(FULL_AWAITING_PICKUP)
                } else if (!isCooldownExpired(ship.cooldown)) {
                    changeState(MINING_COOLDOWN)
                }
                else {
                    extractResult = null
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

    suspend fun mineCallback(res: ExtractionResponse) {
        println("Extract response successful")
        extractResult = res
    }

    suspend fun failback(resp: HttpResponse?, ex: Exception?) {
        NotificationManager.createErrorNotification("Mining failed for ${ship.registration.name}", "bad")
        println("Failback called")
        changeState(MINING)
    }
}

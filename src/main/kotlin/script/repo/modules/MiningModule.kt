package script.repo.modules

import io.ktor.client.statement.*
import model.market.TradeSymbol
import model.responsebody.ExtractionResponse
import model.ship.*
import notification.NotificationManager
import script.Script
import script.ScriptExecutor
import script.actions.mine
import java.time.Instant

class MiningModule<T : Any>(val script: ScriptExecutor<T>) {

    private var extractResult: ExtractionResponse? = null
    lateinit var miningFailedState: T
    lateinit var ship: Ship

    fun addMiningModule(
        ship: Ship, miningState: T, cooldownState: T, yieldState: T,
        keepValuablesState: T, worthlessGoods: List<TradeSymbol>, afterCargoFull: T, s: Script
    ) {
        // by default, just retry mining on fail. Can lead to infinite loops but good enough for now
        miningFailedState = miningState
        this.ship = ship

        with(s) {
            state(script.matchesState(cooldownState)) {
                println("mining cooldown")
                if (isCooldownExpired(ship.cooldown)) {
                    script.changeState(miningState)
                } else {
                    val whenExpiresSeconds = ship.cooldown.expiration?.epochSecond ?: 0
                    val cooldownRemaining = whenExpiresSeconds.minus(Instant.now().epochSecond)
                    ship.cooldown.remainingSeconds = cooldownRemaining
                }
            }

            state(script.matchesState(miningState)) {
                if (cargoFull(ship)) {
                    println("In mining state, but full; Going to await pickup")
                    script.changeState(afterCargoFull)
                } else if (!isCooldownExpired(ship.cooldown)) {
                    script.changeState(cooldownState)
                } else {
                    extractResult = null
                    println("Mining action")
                    mine(ship, ::mineCallback, ::failback)
                    script.changeState(yieldState)
                }
            }

            state(script.matchesState(yieldState)) {
                println("Awaiting mining response...")
                if (extractResult != null) {
                    applyExtractResults(ship, extractResult!!)
                    val yield = extractResult!!.extraction.yield
                    println("Mined ${yield.units} ${yield.symbol}")
                    script.changeState(keepValuablesState)
                }
            }

            state(script.matchesState(keepValuablesState)) {
                println("Ejecting commons")

                // eject garbage
                jettisonCargo(ship, worthlessGoods)

                if (cargoFull(ship)) {
                    script.changeState(afterCargoFull)
                } else {
                    script.changeState(cooldownState)
                }
            }
        }
    }

    suspend fun mineCallback(res: ExtractionResponse) {
        println("Extract response successful")
        extractResult = res
    }

    suspend fun failback(resp: HttpResponse?, ex: Exception?) {
        NotificationManager.createErrorNotification("Mining failed for ${ship.registration.name}")
        println("Failback called")
        script.changeState(miningFailedState)
    }
}
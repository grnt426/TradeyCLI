package script.repo.mining

import model.market.TradeSymbol
import model.ship.Ship
import model.ship.navigateTo
import notification.NotificationManager
import script.ScriptExecutor
import script.repo.mining.SelfSellingMiningScript.SelfSellingMiningStates
import script.repo.mining.SelfSellingMiningScript.SelfSellingMiningStates.*
import script.repo.modules.MiningModule
import script.repo.modules.NavModule
import script.repo.modules.SellModule
import script.script

class SelfSellingMiningScript(val ship: Ship, val assignedAsteroid: String = "X1-RH52-FB5D") :
    ScriptExecutor<SelfSellingMiningStates>(
    MINING, "SelfSellingMiningScript", ship.symbol
) {
    init {
        ship.script = this
        execDelayMs = 1_000
    }

    enum class SelfSellingMiningStates {
        MINING,
        AWAIT_MINING_RESPONSE,
        MINING_COOLDOWN,
        ERROR,
        KEEP_VALUABLES,
        FIND_MARKET_AND_NAV,
        NAV,
        DOCK,
        SELL,
        ORBIT_AND_NAV,
        NAV_TO_MINING,
    }

    private val ALWAYS_WORTHLESS_GOODS = listOf(TradeSymbol.ICE_WATER)

    override fun execute() {
        script {

            // mining handler
            MiningModule(this@SelfSellingMiningScript).addMiningModule(
                ship, MINING, MINING_COOLDOWN, AWAIT_MINING_RESPONSE, KEEP_VALUABLES,
                ALWAYS_WORTHLESS_GOODS, FIND_MARKET_AND_NAV, this
            )

            // Perform selling
            NavModule(this@SelfSellingMiningScript).addNavDockState(
                ship, NAV, DOCK, SELL, true, this
            )
            val s = SellModule(this@SelfSellingMiningScript)
            s.addSellModule(ship, SELL, FIND_MARKET_AND_NAV, this)
            s.addFindMarketToSellAnyGoodsModule(ship, FIND_MARKET_AND_NAV, NAV, ORBIT_AND_NAV, this)

            // Resume mining
            state(matchesState(ORBIT_AND_NAV)) {
                navigateTo(ship, assignedAsteroid)
                changeState(NAV_TO_MINING)
            }
            NavModule(this@SelfSellingMiningScript).addNavState(ship, NAV_TO_MINING, MINING, this)

            state(matchesState(ERROR)) {
                NotificationManager.errorNotification(
                    "SelfSellingMiningScript in error state. Stopping", "Bad"
                )
                stopScript()
            }
        }.runForever(execDelayMs)
    }
}
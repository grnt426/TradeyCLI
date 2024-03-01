package script.repo

import model.GameState
import model.applyAgentUpdate
import model.findShipyardsSellingShipType
import model.purchaseShip
import model.responsebody.ShipPurchaseResponse
import model.ship.Ship
import model.ship.ShipType
import model.ship.navigateTo
import notification.NotificationManager
import script.ScriptExecutor
import script.repo.CommandShipStartScript.ComShipStartState
import script.repo.CommandShipStartScript.ComShipStartState.*
import script.repo.mining.SelfSellingMiningScript
import script.repo.modules.NavModule
import script.repo.pricing.PriceFetcherScript
import script.script

class CommandShipStartScript(val ship: Ship) : ScriptExecutor<ComShipStartState>(
    START, "CommandShipStartScript"
) {
    enum class ComShipStartState {
        START,
        NAV_PROBE,
        DOCK_PROBE,
        BUY_PROBE,
        FIND_MINING_SHIP,
        NAV_MINING,
        DOCK_MINING,
        BUY_MINING,
        DONE,
    }

    var notDone = true

    override fun execute() {
        script {
            state(matchesState(START)) {
                println("Command ship Executing")
                val probeShipyards = findShipyardsSellingShipType(GameState.getHqSystem().symbol, ShipType.SHIP_PROBE)
                if (probeShipyards.isNotEmpty()) {
                    println("Navigating to shipyard")
                    navigateTo(ship, probeShipyards[0].symbol)
                    changeState(NAV_PROBE)
                    NavModule(this@CommandShipStartScript).addNavDockState(
                        ship, NAV_PROBE,
                        DOCK_PROBE, BUY_PROBE,
                        true, this@script
                    )
                } else {
                    println("No shipyard to buy probes")
                    NotificationManager.createErrorNotification("No probe ships to buy??")
                    changeState(FIND_MINING_SHIP)
                }
            }

            state(matchesState(BUY_PROBE)) {
                println("Buying probe")
                purchaseShip(ShipType.SHIP_PROBE, ship.nav.waypointSymbol, ::buyProbeCb)
                changeState(FIND_MINING_SHIP)
            }

            state(matchesState(FIND_MINING_SHIP)) {
                println("Finding mining ships")
                val miningShipyards =
                    findShipyardsSellingShipType(GameState.getHqSystem().symbol, ShipType.SHIP_MINING_DRONE)
                if (miningShipyards.isNotEmpty()) {
                    navigateTo(ship, miningShipyards[0].symbol)
                    changeState(NAV_MINING)
                    NavModule(this@CommandShipStartScript).addNavDockState(
                        ship, NAV_MINING,
                        DOCK_MINING, BUY_MINING,
                        true, this@script
                    )
                } else {
                    NotificationManager.createErrorNotification("No mining drones to buy??")
                    changeState(DONE)
                }
            }

            state(matchesState(BUY_MINING)) {
                println("Buying mining")
                purchaseShip(ShipType.SHIP_MINING_DRONE, ship.nav.waypointSymbol, ::buyMiningCb)
                changeState(DONE)
            }

            state(matchesState(DONE)) {
                println("Done with startup")

                // setup a new script for ourselves.
                // terminate our current script. TODO
                if (notDone) {
                    TradingHaulerScript(ship).execute()
                    notDone = false
                }
            }
        }.runForever(execDelayMs)
    }
}

suspend fun buyProbeCb(purchaseResponse: ShipPurchaseResponse) {
    applyAgentUpdate(purchaseResponse.agent)
    PriceFetcherScript(purchaseResponse.ship).execute()
}

suspend fun buyMiningCb(purchaseResponse: ShipPurchaseResponse) {
    applyAgentUpdate(purchaseResponse.agent)
    SelfSellingMiningScript(purchaseResponse.ship).execute()
}
package script.repo

import io.ktor.client.statement.*
import model.getScriptForShip
import model.ship.*
import model.ship.components.*
import model.system.Waypoint
import script.MessageableScriptExecutor
import script.ScriptExecutor
import script.repo.BasicHaulerScript.*
import script.repo.BasicHaulerScript.HaulingStates.*
import script.repo.BasicMiningScript.*
import script.repo.BasicMiningScript.MiningMessages.*
import script.script
import java.lang.Exception

class BasicHaulerScript(val ship: Ship): ScriptExecutor<HaulingStates>(
    FIND_ELIGIBLE, "BasicMiningScript", ship.symbol
) {

    private var routes = mutableListOf<Ship>()

    private var navComplete = false
    private var shipTarget: Ship? = null

    private var transferredInventory: Inventory? = null
    private var transferFailed = false
    private var transferFromTarget: Ship? = null

    private var targetMarket: Waypoint? = null
    enum class HaulingStates {
        FIND_ELIGIBLE,
        NAV_TO_DROP,
        NAV_TO_COLLECT,
        DOCK_WITH_SHIP,
        TRANSFER_CARGO,
        AWAIT_TRANSFER_COMPLETE,
        DOCK_WITH_MARKET,
        SELL_CARGO,
        AWAIT_NAV_TO_SHIP,
        AWAIT_NAV_TO_MARKET,
        CHOOSE_NEXT_NAV,
        NAV_TO_MARKET,
        CHOOSE_MARKET_TO_SELL,
    }

    override fun execute() {
        script {
            state(matchesState(FIND_ELIGIBLE)) {

                // find excavators that are >=80% full
                // todo: use map to pre-sort ships into systems for faster retrieval and less filtering
                val ships = getShips().filter { shipsInSameSystem(ship, it) }.filter { hasCargoRatio(it, 0.8) }

                if (ships.isNotEmpty()) {
                    // for now, just route in whatever order we collected them in
                    changeState(NAV_TO_COLLECT)
                }
            }

            state(matchesState(NAV_TO_COLLECT)) {
                if (routes.size == 0) {
                    println("Unexpectedly in navigation state with no where to go")
                    changeState(FIND_ELIGIBLE)
                }

                toOrbit(ship)
                shipTarget = routes.first()
                navComplete = false
                navigateTo(ship, shipTarget!!.nav.waypointSymbol, ::navShipCb)
                changeState(AWAIT_NAV_TO_SHIP)
            }

            state(matchesState(AWAIT_NAV_TO_SHIP)) {
                if(navComplete) {
                    navComplete = false
                    changeState(DOCK_WITH_SHIP)
                }
            }

            state(matchesState(DOCK_WITH_SHIP)) {

                // will need to match the ship's dock/orbit status to transfer.
                // for now, assume all excavators are in orbit, so we do nothing
                changeState(TRANSFER_CARGO)
            }

            state(matchesState(TRANSFER_CARGO)) {

                // transfer cargo to this ship, one inventory transfer at a time
                val shipsHere = routes.filter { shipsAtSameWaypoint(ship, it) }
                if (shipsHere.isEmpty() || cargoFull(ship)) {
                    changeState(CHOOSE_NEXT_NAV)
                }
                else {
                    shipsHere.forEach { target ->
                        if (hasCargo(target)) {
                            val inv = findInventoryOfSizeMax(target, cargoSpaceLeft(ship)).toSortedSet { a, b ->
                                b.units - a.units
                            }
                            transferredInventory = inv.first()
                            resetAwaitTransfer()
                            transferCargo(
                                ship, target, transferredInventory!!.symbol, transferredInventory!!.units,
                                ::transferCb, ::transferFb
                            )
                            changeState(AWAIT_TRANSFER_COMPLETE)
                            return@forEach
                        } else {
                            val mailbox = getScriptForShip(target) as MessageableScriptExecutor<MiningStates, MiningMessages>
                            mailbox.postMessage(HAULER_FINISHED)
                            routes.remove(target)
                        }
                    }
                }
            }

            state(matchesState(AWAIT_TRANSFER_COMPLETE)) {

                // regardless of success/fail, continue transferring for now
                if (transferFailed || transferredInventory != null) {
                    resetAwaitTransfer()
                    changeState(TRANSFER_CARGO)
                }
            }

            state(matchesState(CHOOSE_NEXT_NAV)) {
                if (cargoFull(ship) || !hasfuelRatio(ship, 0.1)) {
                    changeState(CHOOSE_MARKET_TO_SELL)
                }
                else {
                    changeState(FIND_ELIGIBLE)
                }
            }

            state(matchesState(CHOOSE_MARKET_TO_SELL)) {


                // for now, a simple choice is just picking a market that can take all our cargo
//                markets.forEach { m -> m. }
                changeState(NAV_TO_MARKET)
            }

            state(matchesState(NAV_TO_MARKET)) {
                if (targetMarket != null) {
                    navigateTo(ship, targetMarket!!.symbol)
                    changeState(AWAIT_NAV_TO_MARKET)
                }
                else {
                    // error, return to find eligible
                    println("Hauler state nav_to_market with no market")
                    changeState(FIND_ELIGIBLE)
                }
            }

            state(matchesState(DOCK_WITH_MARKET)) {
                toDock(ship)
                changeState(SELL_CARGO)
            }

            state(matchesState(SELL_CARGO)) {
                if (hasCargo(ship)) {
                    val inv = ship.cargo.inventory.removeFirst()
                    sellCargo(ship, inv.symbol, inv.units)

                    // stay in this state. Just enqueue next request without validating success/fail
                }
                else {
                    // buy fuel after selling everything. Best chance of affording it
                    buyFuel(ship)
                    changeState(FIND_ELIGIBLE)
                }
            }
        }.runForever(500)
    }

    suspend fun navShipCb(nav: Navigation) {
        navComplete = true
        shipTarget = null
    }

    private fun resetAwaitTransfer() {
        transferFailed = false
        transferredInventory = null
    }

    suspend fun transferCb(targetShipCargo: Cargo) {
        transferFromTarget!!.cargo = targetShipCargo

        // add transferred inventory to our ship
    }

    suspend fun transferFb(resp: HttpResponse?, exception: Exception?) {
        transferFailed = true
    }
}
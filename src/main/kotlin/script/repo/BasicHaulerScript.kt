package script.repo

import io.ktor.client.statement.*
import model.GameState
import model.market.findGoodsAcceptedHere
import model.market.findMarketForGood
import model.ship.*
import model.ship.components.Inventory
import model.ship.components.inv
import model.ship.components.sellCargo
import model.ship.components.transferCargo
import responsebody.NavigationResponse
import responsebody.TransferResponse
import script.ScriptExecutor
import script.repo.BasicHaulerScript.HaulingStates
import script.repo.BasicHaulerScript.HaulingStates.*
import script.script

class BasicHaulerScript(val ship: Ship): ScriptExecutor<HaulingStates>(
    UNKNOWN_START, "BasicMiningScript", ship.symbol
) {

    init {
        ship.script = this
    }

    private var routes = mutableListOf<Ship>()

    private var navComplete = false
    private var shipTarget: Ship? = null

    private var transferredInventory: Inventory? = null
    private var transferFailed = false
    private var transferFromTarget: Ship? = null
    private var transferComplete = false

    private var targetMarket: String? = null
    enum class HaulingStates {
        UNKNOWN_START,

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

            state(matchesState(UNKNOWN_START)) {
                if (hasCargo(ship) && ship.nav.status == ShipNavStatus.IN_TRANSIT) {
                    changeState(AWAIT_NAV_TO_SHIP)
                }
                if (hasCargo(ship)) {
                    changeState(SELL_CARGO)
                }
                else {
                    changeState(FIND_ELIGIBLE)
                }
            }

            state(matchesState(FIND_ELIGIBLE)) {

                // find excavators that are >=80% full
                // todo: use map to pre-sort ships into systems for faster retrieval and less filtering
                val ships = getShips().filter { shipsInSameSystem(ship, it) }.filter { hasCargoRatio(it, 0.8) }

                if (ships.isNotEmpty()) {
                    routes = ships.toMutableList()
                    println("${ships.size} waiting for pickup")
                    // for now, just route in whatever order we collected them in
                    changeState(NAV_TO_COLLECT)
                }
            }

            state(matchesState(NAV_TO_COLLECT)) {
                if (routes.size == 0) {
                    println("Unexpectedly in navigation state with no where to go")
                    changeState(FIND_ELIGIBLE)
                }
                else {
                    println("Navigating to ship")
                    toOrbit(ship)
                    shipTarget = routes.first()

                    // if we are already there, no need to try and burn
                    if (shipsAtSameWaypoint(shipTarget!!, ship)) {
                        changeState(DOCK_WITH_SHIP)
                    }
                    else {
                        navComplete = false
                        navigateTo(ship, shipTarget!!.nav.waypointSymbol, ::navShipCb)
                        changeState(AWAIT_NAV_TO_SHIP)
                    }
                }
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
                            val symbol = transferredInventory!!.symbol
                            val units = transferredInventory!!.units
                            resetAwaitTransfer()
                            transferCargo(ship, target, symbol, units, ::transferCb, ::transferFb)
                            transferFromTarget = target
                            changeState(AWAIT_TRANSFER_COMPLETE)
                            return@forEach
                        } else {
                            routes.remove(target)
                        }
                    }
                }
            }

            state(matchesState(AWAIT_TRANSFER_COMPLETE)) {
                println("Awaiting transfer")

                // regardless of success/fail, continue transferring for now
                if (transferFailed || transferComplete) {
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
                toOrbit(ship)
                println("Picking market to sell at")
                // for now, a simple choice is just picking a market that can take all our cargo
                val goods = ship.cargo.inventory.map { i -> i.symbol }
                val markets = GameState.markets.values
                    .associateBy (
                        keySelector = {it.symbol},
                        valueTransform = { it.imports.map { t -> t.symbol } }
                    ).filterValues { v -> v.containsAll(goods) }

                if (markets.isNotEmpty()) {
                    targetMarket = markets.keys.first()
                    changeState(NAV_TO_MARKET)
                } else {

                    // no smart routing for now. Just pick the first import market that will
                    // buy a good
                    val toSell = ship.cargo.inventory[0].symbol
                    val market = findMarketForGood(toSell, ship.nav.systemSymbol)
                    if (market != null) {
                        targetMarket = market.symbol
                        changeState(NAV_TO_MARKET)
                    }
                    else {
                        // if nothing can take our good, jettison and try again
                        println("Nothing will import ${toSell}; will jettison")
                        jettisonCargo(ship, ship.cargo.inventory.removeFirst())
                    }
                }
            }

            state(matchesState(NAV_TO_MARKET)) {
                println("Navigating to market")
                if (targetMarket != null) {
                    navigateTo(ship, targetMarket!!, ::navShipCb)
                    changeState(AWAIT_NAV_TO_MARKET)
                }
                else {
                    // error, return to find eligible
                    println("Hauler state nav_to_market with no market")
                    changeState(FIND_ELIGIBLE)
                }
            }

            state(matchesState(AWAIT_NAV_TO_MARKET)) {
                if (navComplete) {
                    navComplete = false
                    targetMarket = null
                    changeState(DOCK_WITH_MARKET)
                }
            }

            state(matchesState(DOCK_WITH_MARKET)) {
                println("Docking with market")

                // always buy fuel after docking
                toDock(ship)
                buyFuel(ship)
                changeState(SELL_CARGO)
            }

            state(matchesState(SELL_CARGO)) {
                println("Selling cargo...")

                // find things that can be sold
                if (hasCargo(ship)) {
                    val accepted = findGoodsAcceptedHere(inv(ship), ship.nav.waypointSymbol)
                    if (accepted.isNotEmpty()) {
                        val inv = removeLocalCargo(ship, accepted.first())
                        sellCargo(ship, inv.symbol, inv.units)
                        if (accepted.size == 1)
                            changeState(CHOOSE_MARKET_TO_SELL)
                        // otherwise if we don't change state, the next good will be sold here
                    }
                    else {
                        changeState(CHOOSE_MARKET_TO_SELL)
                    }
                }
                else {
                    changeState(FIND_ELIGIBLE)
                }
            }
        }.runForever(2_000)
    }

    suspend fun navShipCb(nav: NavigationResponse) {
        ship.nav = nav.nav
        ship.fuel = nav.fuel!!
        navComplete = true
        shipTarget = null
    }

    private fun resetAwaitTransfer() {
        transferFailed = false
        transferredInventory = null
        transferComplete = false
    }

    private suspend fun transferCb(targetShipCargo: TransferResponse) {
        println("Got transfer response")
        transferFromTarget!!.cargo = targetShipCargo.cargo

        // now we need to update ourselves, as the API doesn't return our updated cargo back
        // this saves us an API call, but does mean we might be out of sync.
        if (transferredInventory != null) {
            val inv = transferredInventory!!
            val symbol = inv.symbol
            val merge = ship.cargo.inventory.find { i -> i.symbol == symbol }
            val unitsTransferred = inv.units
            ship.cargo.units += unitsTransferred
            if (merge == null) {
                ship.cargo.inventory.add(inv)
            } else {
                merge.units += unitsTransferred
            }

            transferredInventory = null
        }
        transferComplete = true
    }

    private suspend fun transferFb(resp: HttpResponse?, exception: Exception?) {
        transferFailed = true
    }
}
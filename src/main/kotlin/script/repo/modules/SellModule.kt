package script.repo.modules

import model.GameState
import model.market.findGoodsAcceptedHere
import model.market.findMarketForGood
import model.ship.*
import model.ship.components.buySellCargoCbAndApply
import model.ship.components.inv
import model.ship.components.sellCargo
import notification.NotificationManager
import script.Script
import script.ScriptExecutor

class SellModule<T>(val script: ScriptExecutor<T>) {

    /**
     * Will repeatedly sell anything possible at current location until
     * inventory is empty.
     */
    fun addSellModule(ship: Ship, sellState: T, afterSellState: T, s: Script) {
        with(s) {
            state(script.matchesState(sellState)) {
                if (!cargoEmpty(ship)) {
                    script.changeState(afterSellState)
                } else {
                    val accepted = findGoodsAcceptedHere(inv(ship), ship.nav.waypointSymbol)
                    if (accepted.isNotEmpty()) {
                        val inv = removeLocalCargo(ship, accepted.first())
                        sellCargo(ship, inv.symbol, inv.units, ::buySellCargoCbAndApply)
                        if (accepted.size == 1)
                            script.changeState(afterSellState)
                        // otherwise if we don't change state, the next good will be sold here
                    }
                }
            }
        }
    }

    fun addFindMarketToSellAnyGoodsModule(
        ship: Ship, findState: T, afterFindState: T,
        findNothingState: T, s: Script
    ) {
        with(s) {
            state(script.matchesState(findState)) {
                if (cargoEmpty(ship)) {
                    script.changeState(findNothingState)
                } else {
                    // for now, a simple choice is just picking a market that can take all our cargo
                    val goods = ship.cargo.inventory.map { i -> i.symbol }
                    val markets = GameState.markets.values
                        .associateBy(
                            keySelector = { it.symbol },
                            valueTransform = { it.imports.map { t -> t.symbol } }
                        ).filterValues { v -> v.containsAll(goods) }

                    if (markets.isNotEmpty()) {
                        navigateTo(ship, markets.keys.first())
                        script.changeState(afterFindState)
                    } else {

                        // no smart routing for now. Just pick the first import market that will
                        // buy a good
                        val toSell = ship.cargo.inventory[0].symbol
                        val market = findMarketForGood(toSell, ship.nav.systemSymbol)
                        if (market != null) {
                            navigateTo(ship, markets.keys.first())
                            script.changeState(afterFindState)
                        } else {
                            // if nothing can take this good, jettison and try again
                            NotificationManager.createErrorNotification("Nothing will import ${toSell}; will jettison")
                            jettisonCargo(ship, ship.cargo.inventory.removeFirst())
                        }
                    }
                }
            }
        }
    }
}
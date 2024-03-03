package script.repo.modules

import model.ship.Ship
import model.ship.buyFuel
import model.ship.isNavigating
import model.ship.toDock
import script.Script
import script.ScriptExecutor

class NavModule<T>(val script: ScriptExecutor<T>, private var navigationBegun: Boolean = false) {
    fun addNavState(ship: Ship, navigationState: T, afterArrivalState: T, s: Script) {
        with(s) {
            state(script.matchesState(navigationState)) {
                if (isNavigating(ship)) {
                    navigationBegun = true
                }
                if (!isNavigating(ship) && navigationBegun) {
                    script.changeState(afterArrivalState)
                }
            }
        }
    }

    fun addNavDockState(
        ship: Ship, navigationState: T,
        dockState: T, afterDockState: T,
        buyFuel: Boolean, scope: Script
    ) {
        with(scope) {
            state(script.matchesState(navigationState)) {
                if (isNavigating(ship)) {
                    navigationBegun = true
                }
                if (!isNavigating(ship) && navigationBegun) {
                    script.changeState(dockState)
                }
            }

            state(script.matchesState(dockState)) {
                toDock(ship)
                if (buyFuel) buyFuel(ship)
                script.changeState(afterDockState)
            }
        }
    }
}
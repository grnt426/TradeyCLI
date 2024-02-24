package script.repo.modules

import model.ship.Ship
import model.ship.isNavigating
import model.ship.toDock
import script.Script
import script.ScriptExecutor

class NavModule<T>(val script: ScriptExecutor<T>) {
    fun addNavState(ship: Ship, navigationState: T, afterArrivalState: T, s: Script) {
        with(s) {
            state(script.matchesState(navigationState)) {
                if (!isNavigating(ship)) {
                    script.changeState(afterArrivalState)
                }
            }
        }
    }

    fun addNavDockState(
        ship: Ship, navigationState: T,
        dockState: T, afterDockState: T, scope: Script
    ) {
        with(scope) {
            state(script.matchesState(navigationState)) {
                if (!isNavigating(ship)) {
                    script.changeState(dockState)
                }
            }

            state(script.matchesState(dockState)) {
                toDock(ship)
                script.changeState(afterDockState)
            }
        }
    }
}
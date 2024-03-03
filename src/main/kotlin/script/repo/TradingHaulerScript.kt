package script.repo

import client.SpaceTradersClient.ignoredCallback
import io.ktor.client.statement.*
import model.market.Market
import model.ship.*
import script.ScriptExecutor
import script.repo.TradingHaulerScript.TradingHaulerState.*
import script.repo.pricing.PriceFetcherScript
import script.script

class TradingHaulerScript(val ship: Ship) : ScriptExecutor<TradingHaulerScript.TradingHaulerState>(
    INITIAL, "TradingHaulerScript", ship.symbol
) {

    enum class TradingHaulerState {
        INITIAL,
        NAV_NO_ASSIGN,
        ORBIT,
        AWAIT_ASSIGNMENT,
        ASSIGNED_AWAIT_NAV,
        NAV,
        DOCK,
        PICK_UP_GOODS,
        AWAIT_PICKUP,
        FIND_SELL,
        SELL_FOUND_AWAIT_NAV,
        AWAIT_NAV_TO_SELL,
        SELL_GOODS,
        ERROR,
    }

    var market: Market? = null

    override fun execute() {
        println("Executing")
        script {
            state(matchesState(INITIAL)) {
                // resumed while in flight
                if (isNavigating(ship)) {
                    println("Navigating, awaiting arrival")
                    changeState(NAV_NO_ASSIGN)
                }

                // resumed waiting to orbit or about to fetch price
                else if (isDocked(ship)) {
                    println("Changing to in orbit")
                    changeState(ORBIT)
                } else {
                    println("Going to await assignment")
                    changeState(AWAIT_ASSIGNMENT)
                }
            }

            state(matchesState(AWAIT_ASSIGNMENT)) {
//                println("Awaiting assignment")
                // do nothing
            }

            state(matchesState(ASSIGNED_AWAIT_NAV)) {
                println("Assigned awaiting nav")
                if (market == null) {
                    changeState(AWAIT_ASSIGNMENT)
                } else {
                    changeState(NAV)
                    navigateTo(ship, market!!.symbol, ::ignoredCallback, ::failNavCb)
                }
            }

            state(matchesState(NAV)) {
                println("Awaiting nav")

                // resumed in middle of nav
                if (market == null) {
                    changeState(AWAIT_ASSIGNMENT)
                }

                // odd state to get stuck in
                else if (isDocked(ship)) {
                    changeState(ORBIT)
                } else if (isOrbiting(ship)) {
                    changeState(AWAIT_ASSIGNMENT)
                } else {
                    if (isOrbiting(ship)) {
                        changeState(DOCK)
                    }
                }
            }

            state(matchesState(DOCK)) {
                println("docking")
                if (passGuard()) {
                    toDock(ship)
                    refuel(ship)
                }
            }

            state(matchesState(ORBIT)) {
                println("orbiting")
                if (isNavigating(ship)) {
                    changeState(NAV_NO_ASSIGN)
                } else {
                    toOrbit(ship)
                    changeState(AWAIT_ASSIGNMENT)
                }
            }

            state(matchesState(NAV_NO_ASSIGN)) {
                println("nav no assignment")
                if (isOrbiting(ship))
                    changeState(AWAIT_ASSIGNMENT)
            }

            state(matchesState(PriceFetcherScript.PriceFetcherState.ERROR)) {
                println("In error state")
                // stay here
            }
        }.runForever(2_000)
    }

    private fun passGuard(): Boolean {
        return if (isNavigating(ship)) {
            changeState(NAV_NO_ASSIGN)
            false
        } else if (market == null) {
            changeState(AWAIT_ASSIGNMENT)
            false
        } else true
    }

    fun setTarget(target: Market) {
        market = target
        changeState(ASSIGNED_AWAIT_NAV)
    }

    private suspend fun failNavCb(resp: HttpResponse?, exception: Exception?) {
        // try again
        changeState(ASSIGNED_AWAIT_NAV)
    }
}
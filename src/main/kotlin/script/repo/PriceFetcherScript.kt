package script.repo

import io.ktor.client.statement.*
import model.market.Market
import model.market.refreshMarket
import model.ship.*
import script.ScriptExecutor
import script.repo.PriceFetcherScript.PriceFetcherState
import script.repo.PriceFetcherScript.PriceFetcherState.*
import script.script

class PriceFetcherScript(val ship: Ship, state: PriceFetcherState = INITIAL) :
    ScriptExecutor<PriceFetcherState>(state, "PriceFetcherScript", ship.symbol) {

    init {
        ship.script = this
    }

    enum class PriceFetcherState {
        INITIAL,
        AWAIT_ASSIGNMENT,
        ASSIGNED_AWAIT_NAV,
        NAV,
        DOCK,
        GET_PRICE,
        ORBIT,
        NAV_NO_ASSIGN,
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
                println("Awaiting assignment")
                // do nothing
            }

            state(matchesState(ASSIGNED_AWAIT_NAV)) {
                println("Assigned awaiting nav")
                if (market == null) {
                    changeState(AWAIT_ASSIGNMENT)
                } else {
                    if (stillExpired()) {
                        changeState(NAV)
                        navigateTo(ship, market!!.symbol, fb = ::failNavCb)
                    }
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
                    if (stillExpired()) {
                        if (isOrbiting(ship)) {
                            changeState(DOCK)
                        }
                    }
                }
            }

            state(matchesState(DOCK)) {
                println("docking")
                if (passGuard() && stillExpired()) {
                    toDock(ship)
                    changeState(GET_PRICE)
                }
            }

            state(matchesState(GET_PRICE)) {
                println("getting price")
                if (passGuard() && stillExpired()) {
                    if (!isDocked(ship)) {
                        changeState(DOCK)
                    } else {
                        refreshMarket(market!!)
                        changeState(ORBIT)
                    }
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

            state(matchesState(ERROR)) {
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

    private fun stillExpired(): Boolean {
        return with(market) {
            if (this != null) {
                val res = lastRead < getCutoff()
                if (!res)
                    changeState(AWAIT_ASSIGNMENT)
                res
            } else {
                changeState(AWAIT_ASSIGNMENT)
                false
            }
        }
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
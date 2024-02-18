package script.repo

import io.ktor.client.statement.*
import model.GameState
import model.market.Market
import model.market.refreshMarket
import model.responsebody.NavigationResponse
import model.ship.*
import notification.NotificationManager
import script.ScriptExecutor
import script.repo.PriceFetcherScript.PriceFetcherState
import script.repo.PriceFetcherScript.PriceFetcherState.*
import script.script

class PriceFetcherScript(val ship: Ship, state: PriceFetcherState = INITIAL) :
    ScriptExecutor<PriceFetcherState>(state, "PriceFetcherScript", ship.symbol) {

    init {
        ship.script = this
        execDelayMs = 2_000
    }

    enum class PriceFetcherState {
        INITIAL,
        AWAIT_ASSIGNMENT,
        ASSIGNED_AWAIT_NAV,
        NAV,
        AWAIT_NAV_RESP,
        DOCK,
        GET_PRICE,
        ORBIT,
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
                    changeState(DOCK)
                }

                // resumed waiting to orbit or about to fetch price
                else if (isDocked(ship)) {
                    changeState(GET_PRICE)
                } else if (isOrbiting(ship)) {
                    changeState(GET_PRICE)
                } else {
                    println("Going to await assignment")
                    changeState(AWAIT_ASSIGNMENT)
                }
            }

            state(matchesState(AWAIT_ASSIGNMENT)) {
                println("await assign")
                if (isNavigating(ship)) {
                    changeState(NAV)
                } else
                    println("Awaiting assignment")
                // do nothing
            }

            state(matchesState(ASSIGNED_AWAIT_NAV)) {
                println("Assigned await nav")
                if (isNavigating(ship)) {
                    changeState(NAV)
                } else {
                    println("Assigned awaiting nav")
                    if (market == null) {
                        changeState(AWAIT_ASSIGNMENT)
                    } else {
                        if (ship.nav.waypointSymbol == market?.symbol) {
                            changeState(DOCK)
                        } else {
                            navigateTo(ship, market!!.symbol, cb = ::navCb, fb = ::failNavCb)
                            changeState(AWAIT_NAV_RESP)
                        }
                    }
                }
            }

            state(matchesState(AWAIT_NAV_RESP)) {
                println("await nav resp")
                if (isNavigating(ship)) {
                    changeState(NAV)
                } else if (isDocked(ship)) {
                    changeState(GET_PRICE)
                }
            }

            state(matchesState(NAV)) {
                println("nav")

                // resumed in middle of nav
                if (market == null) {
                    market = GameState.markets[ship.nav.route.destination.symbol]

                    // why still null?
                    if (market == null) {
                        changeState(ERROR)
                    }
                }
                else if (isDocked(ship)) {
                    changeState(GET_PRICE)
                } else {
                    if (!isNavigating(ship))
                        changeState(DOCK)
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
                if (market == null) {
                    getLocalMarket()
                }

                println("getting price")
                if (passGuard() && stillExpired()) {
                    if (!isDocked(ship)) {
                        changeState(DOCK)
                    } else {
                        if (market == null) {
                            getLocalMarket()
                            if (market == null) {
                                changeState(ERROR)
                                return@state
                            }
                        }
                        refreshMarket(systemOf(ship), market!!)
                        changeState(ORBIT)
                    }
                } else {
                    changeState(ORBIT)
                }
            }

            state(matchesState(ORBIT)) {
                println("orbiting")
                if (isNavigating(ship)) {
                    changeState(AWAIT_NAV_RESP)
                } else {
                    toOrbit(ship)
                    changeState(AWAIT_ASSIGNMENT)
                }
            }

            state(matchesState(ERROR)) {
                println("In error state")
                // stay here
            }
        }.runForever(execDelayMs)
    }

    private fun passGuard(): Boolean {
        return if (isNavigating(ship)) {
            changeState(NAV)
            false
        } else true
    }

    fun stillExpired(): Boolean {
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

    suspend fun navCb(nav: NavigationResponse) {
        ship.nav = nav.nav
    }

    fun getLocalMarket() {
        market = GameState.markets[ship.nav.waypointSymbol]
    }

    fun setTarget(target: Market) {
        NotificationManager.createNotification("Fetching Price @ ${target.symbol}", "Nothing more")
        market = target
        changeState(ASSIGNED_AWAIT_NAV)
    }

    suspend fun failNavCb(resp: HttpResponse?, exception: Exception?) {
        println("Got error")
        NotificationManager.createErrorNotification(
            "${ship.symbol} failed navigating to ${market?.symbol}", "uhhhh"
        )
        // try again
        changeState(ASSIGNED_AWAIT_NAV)
    }
}
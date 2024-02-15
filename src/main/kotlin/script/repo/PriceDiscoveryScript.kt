package script.repo

import model.GameState
import model.Location
import model.market.Market
import model.ship.Ship
import model.ship.ShipRole
import script.ScriptExecutor
import script.repo.PriceDiscoveryScript.PriceDiscoveryState
import script.repo.PriceDiscoveryScript.PriceDiscoveryState.*
import script.repo.PriceFetcherScript.PriceFetcherState
import script.script
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.math.pow

private const val LAST_READ_TTL_MINUTES = 30L
private val LAST_READ_TTL_UNIT = ChronoUnit.MINUTES

class PriceDiscoveryScript(val system: String, state: PriceDiscoveryState = INITIAL) :
    ScriptExecutor<PriceDiscoveryState>(
        state, "PriceDiscoveryScript", system
    ) {

    enum class PriceDiscoveryState {
        INITIAL,
        FIND_MARKET,
        FIND_SHIP,
        ERROR,
    }

    private val markets: PriorityQueue<Market> = with(GameState.marketsBySystem[system]) {
        if (this != null) PriorityQueue(this) else PriorityQueue()
    }
    private val ships: List<Ship> = GameState.ships.values
        .filter { s -> s.nav.systemSymbol == system && s.registration.role == ShipRole.SATELLITE }
    private val scripts: Map<Ship, PriceFetcherScript> =
        GameState.shipsToScripts.filter { (k, v) -> ships.contains(k) && v is PriceFetcherScript }
                as Map<Ship, PriceFetcherScript>

    override fun execute() {
        script {
            state(matchesState(INITIAL)) {
                if (markets.isNotEmpty()) {
                    println("From start to looking for markets")
                    changeState(FIND_MARKET)
                } else {
                    println("Empty markets? Going to error state...")
                }
            }

            state(matchesState(FIND_MARKET)) {
                // guard check
                if (markets.isEmpty()) {
                    changeState(ERROR)
                }
                println("Finding markets")
                if (markets.peek().lastRead < getCutoff()) {
                    changeState(FIND_SHIP)
                } else {
                    println("Cutoff ${getCutoff()}")
                    println("Next market to fetch ${markets.peek().lastRead} most recent ${markets.last().lastRead}")
                }
            }

            state(matchesState(FIND_SHIP)) {

                println("Finding ships")
                if (ships.isEmpty()) {
                    // buy ships, maybe
                }

                // guard check for our state
                else if (markets.peek().lastRead > getCutoff()) {
                    changeState(FIND_MARKET)
                } else {
                    val available = scripts.filterValues { scripts ->
                        scripts.isState(PriceFetcherState.AWAIT_ASSIGNMENT)
                    }

                    // if all are busy, we stay in this state
                    if (available.isNotEmpty()) {
                        val market = markets.poll()!!
                        val ship = available.keys.minBy { s ->
                            val wp = GameState.waypoints[market.symbol]!!
                            compareDist(
                                s.nav.route.destination,
                                wp.x,
                                wp.y
                            )
                        }
                        available[ship]!!.setTarget(market)
                        changeState(FIND_MARKET)
                    }
                }
            }

            state(matchesState(ERROR)) {
                // hang here
                println("Discovery in error")
            }
        }.runForever(10_000)
    }
}

fun compareDist(loc: Location, x: Int, y: Int): Double =
    ((loc.x - x) + (loc.y - y)).toDouble().pow(2.0)

fun getCutoff(): Instant = Instant.now().minus(LAST_READ_TTL_MINUTES, LAST_READ_TTL_UNIT)

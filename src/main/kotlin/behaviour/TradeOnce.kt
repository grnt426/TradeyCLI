package behaviour

import behaviour.decisions.Selling
import behaviour.decisions.Trading
import behaviour.decisions.TradingAssumptions
import engine.Travel
import engine.VerbFailure
import model.ship.FlightMode

/**
 * One trade load, start to finish: the best route from where the ship is, buy, sell, refuel.
 * For a ship whose main job has nothing for it this hour (a gate hauler whose producer is
 * drawn down): the escape phase's profits fund its logistics. Returns false when no route pays.
 */
suspend fun BehaviourScope.tradeOnce(assumptions: TradingAssumptions, restoreTag: String?): Boolean {
    val now = clock.now()
    shared.releaseRoutes(ship)
    val plan = Trading.rank(snapshot(), me, now, assumptions)
        .filter { !shared.routeTakenByOther("${it.good}@${it.source.symbol}", ship) && !shared.routeTakenByOther("${it.good}@${it.destination.symbol}", ship) }
        .firstOrNull() ?: return false
    shared.claimRoute(ship, "${plan.good}@${plan.source.symbol}", "${plan.good}@${plan.destination.symbol}")
    val tag = restoreTag
    setChain(ship, "trade")
    try {
        status("trade meanwhile", plan.summary())
        phase("travel to source", plan.source.symbol) {
            ensureFuel(Travel.fuelCost(plan.legToSource, FlightMode.CRUISE) + 10)
            travelTo(plan.source.symbol)
        }
        val bought = phase("buy", "${plan.good} at ${plan.source.symbol}") {
            dock(ship)
            val live = refreshMarket(plan.source.symbol)
            if (Trading.stillPays(plan, live, assumptions) == null) 0 else buyLoad(plan, live, assumptions)
        }
        if (bought == 0) return false
        phase("travel to destination", plan.destination.symbol) {
            ensureFuel(Travel.fuelCost(plan.legToDestination, FlightMode.CRUISE) + 10)
            travelTo(plan.destination.symbol)
        }
        phase("sell", "${plan.good} at ${plan.destination.symbol}") {
            dock(ship)
            val market = refreshMarket(plan.destination.symbol)
            val (toSell, toDrop) = Selling.split(me.cargo, market)
            var earned = 0L
            toSell.forEach { line -> earned += sell(ship, line.symbol, line.units).credits; status(detail = "sold ${line.units} ${line.symbol}; +$earned this load") }
            toDrop.forEach { line -> jettison(ship, line.symbol, line.units) }
            try { refuel(ship) } catch (e: VerbFailure) { status(detail = "could not refuel: ${e.message}") }
        }
        return true
    } finally {
        shared.releaseRoutes(ship)
        setChain(ship, tag)
    }
}

package behaviour

import behaviour.decisions.Selling
import model.market.TradeSymbol

/**
 * Sells what the hold carries, except [keep], at the market that pays best for it, and drops
 * what nothing buys. A behaviour that inherits a hold from whatever ran before it calls this
 * first, so a killed trade run does not leave a hauler flying around full of clothing.
 */
suspend fun BehaviourScope.sellLeftovers(keep: Set<TradeSymbol> = emptySet()) {
    val cargo = me.cargo
    val lines = cargo.inventory.filter { it.symbol !in keep }
    if (lines.isEmpty()) return
    val market = Selling.bestMarketFor(cargo.copy(inventory = lines), here, snapshot())
    if (market == null) {
        lines.forEach { jettison(ship, it.symbol, it.units) }
        return
    }
    travelTo(market.symbol)
    dock(ship)
    val live = refreshMarket(market.symbol)
    val (toSell, toDrop) = Selling.split(me.cargo.let { c -> c.copy(inventory = c.inventory.filter { it.symbol !in keep }) }, live)
    toSell.forEach { sell(ship, it.symbol, it.units) }
    toDrop.forEach { jettison(ship, it.symbol, it.units) }
}

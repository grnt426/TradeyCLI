package behaviour

import behaviour.BehaviourScope.Companion.buyableAt
import behaviour.decisions.Intentions
import behaviour.decisions.Tour
import kotlin.time.Duration.Companion.minutes

/**
 * The buyer: park at a shipyard that sells what the fleet goal wants and buy it the moment the
 * bank clears price plus reserve. Any ship of ours docked at the yard satisfies the purchase
 * rule, so the probe, which flies for free and has nothing else to do once the survey is done,
 * is the natural choice. Bought ships are handed to the supervisor, which puts them to work.
 */
val expandSpec = BehaviourSpec(
    name = "expand",
    description = "Park at a shipyard and buy the ships the fleet goal asks for as soon as the bank allows.",
    params = listOf(
        ParamSpec("shipyard", "Where to wait; default: the nearest yard selling the first wanted type"),
        ParamSpec("every", "Minutes between checks of the listing and the bank (default 2)"),
    ),
    validate = { snapshot, ship, params ->
        buildList {
            params["shipyard"]?.let { y ->
                val w = snapshot.waypoints[y]
                if (w == null) add("unknown waypoint $y") else if (!w.hasShipyard) add("$y has no shipyard")
                else if (w.systemSymbol != ship.nav.systemSymbol) add("$y is not in ${ship.nav.systemSymbol}")
            }
        }
    },
    run = { expand() },
)

suspend fun BehaviourScope.expand() {
    val fixedYard = param("shipyard")?.uppercase()
    val every = (param("every")?.toLongOrNull() ?: 2).minutes
    while (true) {
        val goals = shared.goals.fleet
        val fleet = snapshot().ships.values
        val wanted = goals.filter { it.buyableAt(me.nav.systemSymbol, shared.plan) }.firstOrNull { goal -> goal.owned(fleet) < goal.count }
        if (wanted == null) {
            // Nothing to buy: a probe parked at a yard is a probe not reading prices. One pass over stale markets, then check again.
            val stale = clock.now().minusSeconds(10 * 60)
            val next = snapshot().waypointsIn(me.nav.systemSymbol).filter { w -> w.hasMarket && (snapshot().markets[w.symbol]?.let { !it.hasPrices || it.lastRead.isBefore(stale) } ?: true) }
                .let { Tour.nearest(here, it) }
            if (next != null && !me.usesFuel) {
                phase("read prices while idle", next.symbol) { travelTo(next.symbol); refreshMarket(next.symbol) }
                continue
            }
            status("idle", if (goals.isEmpty()) "no fleet goal; `goal fleet TYPE N` to set one" else "every fleet goal is met; parked at ${here.symbol}")
            clock.sleep(every * 2)
            continue
        }
        val yard = fixedYard ?: phase("choose yard") {
            val snap = snapshot()
            val selling = snap.waypointsIn(me.nav.systemSymbol).filter { w -> snap.shipyards[w.symbol]?.sells(wanted.type) == true }
            Tour.nearest(here, selling)?.symbol ?: throw BehaviourFailure("no shipyard in ${me.nav.systemSymbol} sells ${wanted.type}")
        }
        if (me.nav.waypointSymbol != yard) phase("travel to yard", yard) { travelTo(yard) }
        dock(ship)
        val bought = phase("buy", "at $yard for ${wanted.type.name.removePrefix("SHIP_")}") { maybeExpand() }
        if (bought != null) continue
        val price = snapshot().shipyards[yard]?.priceOf(wanted.type)
        val owned = wanted.owned(snapshot().ships.values)
        status(
            "waiting",
            "at $yard for ${wanted.type.name.removePrefix("SHIP_")} #${owned + 1} of ${wanted.count}" +
                (price?.let { ": ${Intentions.format(agent().credits)} of ${Intentions.format(it + wanted.reserve)} needed" } ?: ""),
        )
        clock.sleep(every)
    }
}

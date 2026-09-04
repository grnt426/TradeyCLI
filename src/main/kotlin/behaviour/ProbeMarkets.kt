package behaviour

import behaviour.decisions.Tour
import model.system.Waypoint
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The survey of the system: visit every market whose prices are unknown (or, with `maxAge`,
 * stale), read them, and read the shipyard if there is one. Several ships may run this at once;
 * each claims the market it is heading for. Finishes when nothing is left to read, so with
 * `maxAge` set on a slow ship it may never finish, which is the point of that mode.
 */
val probeMarketsSpec = BehaviourSpec(
    name = "probeMarkets",
    description = "Visit every market in the system and record its prices (and shipyard listings).",
    params = listOf(
        ParamSpec("system", "System to cover; default: the ship's"),
        ParamSpec("markets", "Comma-separated waypoints to restrict to"),
        ParamSpec("maxAge", "Minutes before a reading counts as stale and is taken again; without it, each market is read once and the behaviour finishes"),
    ),
    validate = { _, _, _ -> emptyList() },
    run = { probeMarkets() },
)

suspend fun BehaviourScope.probeMarkets() {
    val system = param("system") ?: me.nav.systemSymbol
    val only = param("markets")?.split(',')?.map { it.trim().uppercase() }?.toSet()
    val maxAge = param("maxAge")?.toLongOrNull()?.minutes
    var read = 0
    val unreadable = mutableSetOf<String>()
    while (true) {
        val snap = snapshot()
        val now = clock.now()
        val stale = maxAge?.let { now.minusSeconds(it.inWholeSeconds) }
        val candidates = snap.waypointsIn(system).filter { w ->
            w.hasMarket && (only == null || w.symbol in only) && w.symbol !in unreadable &&
                !shared.claimedByOther(w.symbol, ship) && needsReading(snap, w, stale)
        }
        val next = Tour.nearest(here, candidates) ?: break
        shared.claim(ship, next.symbol)
        phase("travel", "to ${next.symbol} (${distanceTo(next.symbol).toInt()} away)") { travelTo(next.symbol) }
        phase("read prices", next.symbol) {
            val market = refreshMarket(next.symbol)
            if (next.hasShipyard) refreshShipyard(next.symbol)
            if (market.hasPrices) read++ else {
                // The server shows prices only with a ship present; if it still shows none, do not spin on it.
                unreadable += next.symbol
                status(detail = "${next.symbol} showed no prices; skipping it")
                clock.sleep(5.seconds)
            }
        }
    }
    shared.release(ship)
    status("done", "read $read markets; nothing left to read" + (if (unreadable.isEmpty()) "" else "; unreadable: ${unreadable.joinToString(",")}"))
}

private fun needsReading(snap: engine.Snapshot, w: Waypoint, stale: Instant?): Boolean {
    val market = snap.markets[w.symbol] ?: return true
    if (!market.hasPrices) return true
    return stale != null && market.lastRead.isBefore(stale)
}

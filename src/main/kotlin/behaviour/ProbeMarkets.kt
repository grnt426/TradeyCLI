package behaviour

import engine.VerbFailure
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
    val unreadable = mutableMapOf<String, Instant>()
    while (true) {
        val snap = snapshot()
        val now = clock.now()
        val stale = maxAge?.let { now.minusSeconds(it.inWholeSeconds) }
        // A market that showed nothing is tried again after maxAge (the ship may not have counted as present); without maxAge it is skipped for good.
        unreadable.entries.removeIf { (_, at) -> maxAge != null && at.isBefore(now.minusSeconds(maxAge.inWholeSeconds)) }
        val candidates = snap.waypointsIn(system).filter { w ->
            w.hasMarket && (only == null || w.symbol in only) && w.symbol !in unreadable &&
                !shared.claimedByOther(w.symbol, ship) && needsReading(snap, w, stale)
        }
        val next = Tour.nearest(here, candidates)
        if (next == null) {
            if (maxAge == null) break
            // Watching: everything is fresh; wait for the oldest reading to age out rather than finish.
            shared.release(ship)
            status("watching", "${only?.joinToString(",") ?: system} all read within $maxAge; next pass when a reading ages")
            clock.sleep(minOf(maxAge / 2, 2.minutes))
            continue
        }
        shared.claim(ship, next.symbol)
        phase("travel", "to ${next.symbol} (${distanceTo(next.symbol).toInt()} away)") { travelTo(next.symbol) }
        phase("read prices", next.symbol) {
            var market = refreshMarket(next.symbol)
            if (!market.hasPrices) {
                // The server shows prices only once it counts the ship as present, which can lag our
                // arrival by a few seconds: dock (free, and impossible until arrived) and read again.
                clock.sleep(5.seconds)
                try { dock(ship) } catch (e: VerbFailure) { status(detail = "${next.symbol}: could not dock yet (${e.message})") }
                market = refreshMarket(next.symbol)
            }
            if (next.hasShipyard) refreshShipyard(next.symbol)
            if (market.hasPrices) read++ else {
                // Still nothing with the ship docked: this market really shows no prices; do not spin on it.
                unreadable[next.symbol] = clock.now()
                status(detail = "${next.symbol} showed no prices even docked; skipping it" + (if (maxAge != null) " until the next pass" else ""))
                clock.sleep(5.seconds)
            }
        }
    }
    shared.release(ship)
    status("done", "read $read markets; nothing left to read" + (if (unreadable.isEmpty()) "" else "; unreadable: ${unreadable.keys.joinToString(",")}"))
}

private fun needsReading(snap: engine.Snapshot, w: Waypoint, stale: Instant?): Boolean {
    val market = snap.markets[w.symbol] ?: return true
    if (!market.hasPrices) return true
    return stale != null && market.lastRead.isBefore(stale)
}

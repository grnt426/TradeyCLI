package behaviour

import kotlin.time.Duration.Companion.minutes

/**
 * A ship with nothing worth a request: it sits where it is and makes none. The reserve the boom
 * draws on when a pioneer opens a system (docs/boom.md). On 2026-09-07 fifty-six idle probes
 * touring their systems' markets on a timer were a third of the account's API budget.
 */
val parkSpec = BehaviourSpec(
    name = "park",
    description = "Sit still and make no requests until reassigned.",
    params = emptyList(),
    validate = { _, _, _ -> emptyList() },
    run = { park() },
)

suspend fun BehaviourScope.park() {
    while (true) {
        status("parked", "at ${me.nav.waypointSymbol}; no requests until reassigned")
        clock.sleep(30.minutes)
    }
}

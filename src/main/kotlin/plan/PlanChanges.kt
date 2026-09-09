package plan

/**
 * What changed between two plans, in the few words the bridge's feed wants: the phase moving, the
 * gate rush switching, a system entering or leaving the plan, a system's stage moving. Nothing
 * about assignments, goals or the frontier, which change too often to be worth a line.
 */
object PlanChanges {
    fun describe(before: Plan, after: Plan, why: String): List<String> {
        val out = ArrayList<String>()
        val because = if (why.isBlank()) "" else " ($why)"
        if (before.phase != after.phase) out += "phase ${before.phase} → ${after.phase}: ${knowledge.Strategy.describe(after.phase)}$because"
        if (before.rushing != after.rushing) out += (if (after.rushing) "the gate rush is on" else "the gate rush is off") + because
        for ((symbol, record) in after.systems) {
            val was = before.systems[symbol]
            when {
                was == null -> out += "$symbol enters the plan at ${record.stage}" + (record.pioneer?.let { ", pioneered by $it" } ?: "") + because
                was.stage != record.stage -> out += "$symbol: ${was.stage} → ${record.stage}$because"
            }
        }
        for (symbol in before.systems.keys - after.systems.keys) out += "$symbol leaves the plan$because"
        return out
    }
}

package behaviour.decisions

import java.time.Duration
import java.time.Instant

/** One agent's credits and ship count at one moment, from the public agent list. */
data class AgentSample(val symbol: String, val at: Instant, val credits: Long, val ships: Int)

/** An agent's rate over the sampled window. Credits spent on ships or a gate hide income: the rate is a floor on what the agent earns. */
data class AgentRate(
    val symbol: String,
    val ships: Int,
    val shipsBought: Int,
    val credits: Long,
    val earned: Long,
    val hours: Double,
) {
    val perHour: Double get() = if (hours > 0) earned / hours else 0.0
    val perShipHour: Double get() = if (ships > 0) perHour / ships else 0.0
}

/**
 * What the public agent list can tell us about the competition. We never see another agent's
 * ship types or trades; we do see their bank and ship count every half hour, and the question
 * "does a fleet twice ours earn 30% more?" is answered by credits per hour per ship over the same
 * hours, on the same server day.
 */
object Leaders {
    /** Each agent's rate from its first to its last sample within the window, agents with under [minHours] of samples left out. */
    fun rates(samples: List<AgentSample>, minHours: Double = 0.5): List<AgentRate> =
        samples.groupBy { it.symbol }.values.mapNotNull { history ->
            val first = history.minBy { it.at }
            val last = history.maxBy { it.at }
            val hours = Duration.between(first.at, last.at).toMillis() / 3_600_000.0
            if (hours < minHours) null
            else AgentRate(last.symbol, last.ships, last.ships - first.ships, last.credits, last.credits - first.credits, hours)
        }.sortedByDescending { it.perHour }
}

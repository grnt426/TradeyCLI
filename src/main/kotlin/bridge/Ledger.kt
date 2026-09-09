package bridge

import behaviour.decisions.Summary
import engine.Snapshot
import model.market.TransactionType
import java.time.Instant

/** One credit move, attributed the way [Summary]'s flows are: when, the purpose or source, and how much. */
data class MoneyEvent(val at: Instant, val category: String, val credits: Long)

/**
 * The ledger as events in time rather than totals, so the economy screen can chart how each
 * purpose's cost and each source's revenue have grown over the reset. Same attribution as
 * [Summary.spending] and [Summary.revenue]: a market purchase or sale goes to the tag the ship
 * carried, a credit move outside a market to the ledger's kind.
 */
object MoneyFlows {
    fun spending(snapshot: Snapshot): List<MoneyEvent> {
        val out = ArrayList<MoneyEvent>()
        snapshot.taggedTransactions.forEach { t ->
            if (t.transaction.type == TransactionType.PURCHASE) out += MoneyEvent(Instant.parse(t.transaction.timestamp), Summary.purposeOf(t.tag), t.transaction.totalPrice.toLong())
        }
        snapshot.ledger.forEach { e -> if (e.credits < 0) out += MoneyEvent(e.at, if (e.kind == "ships") Summary.SHIPS else Summary.OTHER, -e.credits) }
        return out.sortedBy { it.at }
    }

    fun revenue(snapshot: Snapshot): List<MoneyEvent> {
        val out = ArrayList<MoneyEvent>()
        snapshot.taggedTransactions.forEach { t ->
            if (t.transaction.type == TransactionType.SELL) out += MoneyEvent(Instant.parse(t.transaction.timestamp), Summary.sourceOf(t.tag), t.transaction.totalPrice.toLong())
        }
        snapshot.ledger.forEach { e -> if (e.credits > 0) out += MoneyEvent(e.at, when (e.kind) { "chart" -> Summary.CHARTING; "contract" -> Summary.CONTRACTS; else -> Summary.OTHER }, e.credits) }
        return out.sortedBy { it.at }
    }

    /**
     * Running totals per category, oldest first, largest category first: each series starts at
     * zero when its first credit moves and steps up with every one after. Every category's line
     * also carries a point at the first event's time so the chart's span starts together.
     */
    fun cumulative(events: List<MoneyEvent>): Map<String, List<Pair<Instant, Long>>> {
        if (events.isEmpty()) return emptyMap()
        val start = events.first().at
        val running = LinkedHashMap<String, Long>()
        val series = LinkedHashMap<String, MutableList<Pair<Instant, Long>>>()
        for (e in events) {
            val total = (running[e.category] ?: 0L) + e.credits
            running[e.category] = total
            series.getOrPut(e.category) { mutableListOf(start to 0L) } += e.at to total
        }
        return series.entries.sortedByDescending { running[it.key] ?: 0L }.associate { it.key to it.value.toList() }
    }
}

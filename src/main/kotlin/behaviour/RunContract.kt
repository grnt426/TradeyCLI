package behaviour

import behaviour.decisions.Intentions
import behaviour.decisions.Tour
import engine.VerbFailure
import model.contract.Contract
import model.market.TradeSymbol
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/**
 * The contract runner: take whatever contract the faction offers, buy what it asks for at the
 * cheapest market that sells it, deliver, fulfil, negotiate the next one. Every contract's
 * payment and our cost go to the store, so whether contracts grow in value with each one done
 * is a question the data answers.
 */
val runContractSpec = BehaviourSpec(
    name = "runContract",
    description = "Negotiate, accept, procure, deliver and fulfil contracts, one after another, recording each one's economics.",
    params = listOf(ParamSpec("maxLoss", "Skip a contract whose goods would cost more than its payment plus this many credits (default 0)")),
    validate = { _, ship, _ -> buildList { if (ship.cargo.capacity == 0) add("${ship.symbol} has no cargo hold") } },
    run = { runContract() },
)

suspend fun BehaviourScope.runContract() {
    val maxLoss = param("maxLoss")?.toLongOrNull() ?: 0L
    while (true) {
        val now = clock.now()
        val contract = phase("find contract") { openContract(now) ?: negotiate() }
        if (contract == null) {
            status("waiting", "no contract on offer and nowhere to negotiate; trying again in 10 minutes")
            clock.sleep(10.minutes)
            continue
        }
        val term = contract.terms.deliver.firstOrNull() ?: run {
            status("skip", "${contract.id.takeLast(6)} has no delivery terms")
            clock.sleep(10.minutes)
            continue
        }
        val good = term.tradeSymbol
        val remaining = (term.unitsRequired - term.unitsFulfilled).toInt()
        val payment = contract.terms.payment.onAccepted + contract.terms.payment.onFulfilled
        val source = cheapestSource(good)
        val estimatedCost = source?.let { (m, price) -> price.toLong() * remaining } ?: 0L
        if (source != null && estimatedCost > payment + maxLoss && !contract.accepted) {
            status("skip", "${contract.id.takeLast(6)}: $remaining $good would cost ~${Intentions.format(estimatedCost)} against ${Intentions.format(payment)} paid; waiting for the offer to lapse")
            clock.sleep(30.minutes)
            continue
        }
        var accepted = contract
        if (!accepted.accepted) accepted = phase("accept", "${contract.id.takeLast(6)}: $remaining $good to ${term.destinationSymbol} for ${Intentions.format(payment)}") { acceptContract(contract.id) }

        // Procure and deliver until the term is met.
        while (true) {
            val current = contracts().firstOrNull { it.id == accepted.id } ?: accepted
            val t = current.terms.deliver.first { it.tradeSymbol == good }
            val left = (t.unitsRequired - t.unitsFulfilled).toInt()
            if (left <= 0) break
            val have = me.unitsOf(good)
            if (have == 0) {
                val (market, _) = cheapestSource(good) ?: throw BehaviourFailure("nothing in the system sells $good for contract ${accepted.id.takeLast(6)}")
                phase("procure", "$left $good at $market") {
                    travelTo(market)
                    dock(ship)
                    refreshMarket(market)
                    val want = minOf(left, me.cargoSpaceLeft)
                    val bought = purchase(ship, good, want)
                    if (bought.units == 0) throw BehaviourFailure("$market sold no $good")
                    status(detail = "bought ${bought.units} $good for ${Intentions.format(bought.credits)}")
                }
            }
            phase("deliver", "${me.unitsOf(good)} $good to ${t.destinationSymbol}") {
                travelTo(t.destinationSymbol)
                dock(ship)
                deliverContract(accepted.id, ship, good, minOf(me.unitsOf(good), left))
            }
        }
        phase("fulfil", accepted.id.takeLast(6)) { fulfillContract(accepted.id) }
        status("done", "${accepted.id.takeLast(6)} fulfilled for ${Intentions.format(payment)}; negotiating the next")
    }
}

/** The accepted, unfulfilled contract if there is one, else an offer that can still be accepted. */
private fun BehaviourScope.openContract(now: Instant): Contract? {
    val all = contracts()
    return all.firstOrNull { it.accepted && !it.fulfilled && Instant.parse(it.terms.deadline).isAfter(now) }
        ?: all.firstOrNull { !it.accepted && !it.fulfilled && (it.deadlineToAccept?.let { d -> Instant.parse(d).isAfter(now) } ?: true) }
}

/** Goes to the nearest waypoint with a faction and asks for a contract. Null when none is reachable. */
private suspend fun BehaviourScope.negotiate(): Contract? {
    val snap = snapshot()
    val factionWaypoints = snap.waypointsIn(me.nav.systemSymbol).filter { it.faction != null }
    val target = if (here.faction != null) here else Tour.nearest(here, factionWaypoints) ?: return null
    phase("negotiate", "at ${target.symbol}") {
        travelTo(target.symbol)
        dock(ship)
    }
    return try {
        negotiateContract(ship)
    } catch (e: VerbFailure.Api) {
        status(detail = "could not negotiate: ${e.error.apiMessage}")
        null
    }
}

/** The market with the lowest known purchase price for [good], with that price. */
private fun BehaviourScope.cheapestSource(good: TradeSymbol): Pair<String, Int>? =
    snapshot().marketsIn(me.nav.systemSymbol)
        .mapNotNull { m -> m.good(good)?.purchasePrice?.let { m.symbol to it } }
        .minByOrNull { it.second }

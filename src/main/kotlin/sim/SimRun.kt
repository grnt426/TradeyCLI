package sim

import engine.Event
import engine.ShipStatus
import engine.ShipVerbs
import engine.VerbSink
import engine.World
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import model.Agent
import model.Shipyard
import model.actions.Survey
import model.market.Market
import model.market.MarketTransaction
import model.market.TransactionType
import model.ship.Ship
import model.ship.ShipType
import model.system.Waypoint
import plan.FleetGoal
import plan.Plan
import plan.Supervisor
import storage.ExtractionRecord
import java.time.Instant
import kotlin.time.Duration.Companion.hours

/** Records what the verbs report, for tests and the sim report. Nothing is persisted. */
class TraceSink : VerbSink {
    val events = mutableListOf<Event>()
    val phases = mutableListOf<Pair<String, ShipStatus>>()
    val transactions = mutableListOf<MarketTransaction>()
    val extractions = mutableListOf<ExtractionRecord>()

    override suspend fun shipChanged(ship: Ship) {}
    override suspend fun agentChanged(agent: Agent) {}
    override suspend fun marketChanged(market: Market) { events += Event.MarketUpdated(market.symbol) }
    override suspend fun shipyardChanged(shipyard: Shipyard) {}
    override suspend fun waypointChanged(waypoint: Waypoint) {}
    override suspend fun surveysAdded(surveys: List<Survey>) {}
    override suspend fun transaction(transaction: MarketTransaction, chain: String?) { transactions += transaction; chain?.let { tagged += transaction to it } }
    val tagged = mutableListOf<Pair<MarketTransaction, String>>()
    override suspend fun extraction(record: ExtractionRecord) { extractions += record }
    override suspend fun statusChanged(ship: String, status: ShipStatus?, params: String) {
        if (status != null) phases += ship to status
    }
    override suspend fun supplied(record: storage.SupplyRecord) { supplies += record }
    val supplies = mutableListOf<storage.SupplyRecord>()
    override suspend fun contractChanged(contract: model.contract.Contract, cost: Long, accepted: Boolean, fulfilled: Boolean) {
        contracts[contract.id] = contract
    }
    val contracts = mutableMapOf<String, model.contract.Contract>()
    override fun event(event: Event) { events += event }

    fun phaseNames(ship: String): List<String> = phases.filter { it.first == ship }.map { it.second.phase }
}

/** What a simulated stretch produced. */
data class SimReport(
    val hours: Int,
    val startingCredits: Long,
    val endingCredits: Long,
    val creditsByHour: List<Long>,
    val calls: Int,
    val extractions: Int,
    val unitsExtracted: Int,
    val unitsSold: Int,
    val fuelSpent: Long,
    val goodsBought: Long,
    val salesByGood: Map<String, Pair<Int, Long>>,
    val asteroids: Map<String, String>,
    val failures: List<String>,
    val finalStatus: Map<String, String>,
    val trace: TraceSink,
) {
    val earned: Long get() = endingCredits - startingCredits
    val perHour: Double get() = if (hours == 0) 0.0 else earned.toDouble() / hours
    val callsPerHour: Double get() = if (hours == 0) 0.0 else calls.toDouble() / hours
}

/**
 * Runs a plan against a [SimUniverse] on virtual time: a day of mining takes milliseconds. The
 * same [ShipVerbs] and [Supervisor] the live client uses drive it, so what passes here is what
 * runs live.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimRun(
    private val seed: SimSeed,
    private val plan: Plan,
    private val hours: Int,
    private val rules: SimRules = SimRules(),
    private val randomSeed: Long = 1,
    private val start: Instant = Instant.parse("2026-09-04T12:00:00Z"),
    /** Ships to buy as soon as a trader docks at a yard that sells them; they get the default behaviour. */
    private val purchases: List<ShipType> = emptyList(),
) {
    fun run(): SimReport {
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(StandardTestDispatcher(scheduler) + SupervisorJob())
        val clock = VirtualClock(scheduler, start)
        val universe = SimUniverse(seed, clock, rules, randomSeed)
        val world = worldFrom(universe, seed)
        val trace = TraceSink()
        val verbs = ShipVerbs(SimApi(universe), world, clock, trace)
        val supervisor = Supervisor(scope, verbs, clock, trace::event)
        // Purchases become fleet goals with no reserve: a trader buys them the first time it docks at a yard that lists them.
        val effectivePlan = purchases.groupBy { it }.entries.fold(plan) { p, (type, list) ->
            p.withGoal(FleetGoal(type, list.size + seed.ships.count { behaviour.BehaviourScope.shipTypeOf(it) == type }, reserve = 0))
        }
        val problems = supervisor.apply(effectivePlan)
        require(problems.isEmpty()) { "plan is not valid: ${problems.joinToString("; ")}" }

        val samples = mutableListOf<Long>()
        scope.launch {
            repeat(hours) {
                clock.sleep(1.hours)
                samples += universe.agent.credits
            }
        }
        advanceWithWatchdog(scheduler, hours, trace, clock)
        supervisor.stopAll()
        scope.cancel()

        val sales = universe.transactions.filter { it.type == TransactionType.SELL }
        val fuel = universe.transactions.filter { it.type == TransactionType.PURCHASE && it.tradeSymbol == model.market.TradeSymbol.FUEL }.sumOf { it.totalPrice.toLong() }
        val goodsBought = universe.transactions.filter { it.type == TransactionType.PURCHASE && it.tradeSymbol != model.market.TradeSymbol.FUEL }.sumOf { it.totalPrice.toLong() }
        return SimReport(
            hours = hours,
            startingCredits = seed.agent.credits,
            endingCredits = universe.agent.credits,
            creditsByHour = samples,
            calls = universe.calls,
            extractions = universe.extractions.size,
            unitsExtracted = universe.extractions.sumOf { it.third },
            unitsSold = sales.sumOf { it.units },
            fuelSpent = fuel,
            goodsBought = goodsBought,
            salesByGood = sales.groupBy { it.tradeSymbol.name }.mapValues { (_, t) -> t.sumOf { it.units } to t.sumOf { it.totalPrice.toLong() } },
            asteroids = universe.asteroidReport(),
            failures = trace.events.filterIsInstance<Event.BehaviourFailed>().map { "${it.ship}: ${it.reason}" },
            finalStatus = world.shipStatus.mapValues { (_, s) -> "${s.behaviour}: ${s.phase} ${s.detail}".trim() },
            trace = trace,
        )
    }

    /**
     * Advances virtual time an hour at a time on a helper thread. A behaviour that loops without
     * ever waiting would spin the scheduler forever; if an hour of virtual time takes longer than
     * [STALL_SECONDS] of real time, the run is abandoned and the last phases are reported so the
     * loop can be found.
     */
    private fun advanceWithWatchdog(scheduler: TestCoroutineScheduler, hours: Int, trace: TraceSink, clock: VirtualClock) {
        for (hour in 1..hours) {
            var failure: Throwable? = null
            val worker = Thread({
                try {
                    scheduler.advanceTimeBy(1.hours.inWholeMilliseconds)
                    scheduler.runCurrent()
                } catch (t: Throwable) {
                    failure = t
                }
            }, "sim-hour-$hour").apply { isDaemon = true }
            worker.start()
            worker.join(STALL_SECONDS * 1000L)
            if (worker.isAlive) {
                val recent = synchronized(trace) { trace.phases.takeLast(8).joinToString("\n") { (ship, s) -> "  ${s.since} $ship ${s.behaviour}: ${s.phase} ${s.detail}" } }
                throw IllegalStateException("simulation stalled in hour $hour at virtual ${clock.now()}: a behaviour is looping without waiting. Last phases:\n$recent")
            }
            failure?.let { throw it }
        }
    }

    companion object {
        const val STALL_SECONDS = 20

        /**
         * A world seeded the way a boot would seed it: everything known, and the prices the store
         * remembers from earlier visits (the [seed]'s), plus live ones where a ship stands.
         */
        fun worldFrom(universe: SimUniverse, seed: SimSeed? = null): World = World().apply {
            agent = universe.agent()
            serverStatus = universe.status()
            systems[universe.system.symbol] = universe.system
            universe.listWaypoints(universe.system.symbol).forEach { waypoints[it.symbol] = it }
            seed?.markets?.forEach { markets[it.symbol] = it }
            universe.markets.keys.forEach { symbol -> universe.market(symbol).let { if (it.hasPrices || markets[symbol] == null) markets[symbol] = it } }
            seed?.shipyards?.forEach { shipyards[it.symbol] = it }
            universe.shipyards.keys.forEach { symbol -> universe.shipyard(symbol).let { if (it.ships.isNotEmpty() || shipyards[symbol] == null) shipyards[symbol] = it } }
            universe.listShips().forEach { ships[it.symbol] = it }
        }

        /** The plan that needs no telling: every ship gets its default behaviour. */
        fun defaultPlan(ships: Collection<Ship>): Plan = Plan(
            assignments = ships.sortedBy { it.symbol }.mapNotNull { ship -> behaviour.Behaviours.defaultFor(ship)?.let { plan.Assignment(ship.symbol, it) } },
        )
    }
}

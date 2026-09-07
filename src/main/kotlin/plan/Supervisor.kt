package plan

import behaviour.BehaviourScope
import behaviour.Behaviours
import behaviour.SharedState
import engine.Event
import engine.GameClock
import engine.Verbs
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Runs the plan: one coroutine per assignment on [scope], driving [verbs]. A behaviour that
 * throws is reported and restarted with backoff; one that returns is finished and left alone.
 * Removing an assignment cancels its coroutine. Behaviours never start each other; they edit the
 * plan and the supervisor reacts.
 */
class Supervisor(
    private val scope: CoroutineScope,
    private val verbs: Verbs,
    private val clock: GameClock,
    private val emit: (Event) -> Unit,
    private val shared: SharedState = SharedState(),
    /** Called with the plan whenever the supervisor changes it itself (a bought ship gets an assignment). */
    private val savePlan: (Plan) -> Unit = {},
    /** Reads the plan as it is on disk, so an `assign` typed while the run is going is applied rather than overwritten. */
    private val loadPlan: () -> Plan? = { null },
) {
    private val running = ConcurrentHashMap<String, Running>()

    var plan: Plan = Plan()
        private set

    init {
        shared.onShipPurchased = { ship, forSystem ->
            val assignment = knowledge.Strategy.defaultAssignment(plan.phase, ship, verbs.snapshot().copy(plan = loadPlan() ?: plan), forSystem)
            if (assignment != null) change("assign ${assignment.behaviour} to ${ship.symbol}") { it.with(assignment) }
        }
        shared.onPhaseChanged = { phase ->
            logger.info { "phase ${plan.phase} -> $phase" }
            change("move to phase $phase") { it.withPhase(phase) }
            emit(Event.PhaseAdvanced(phase.name, knowledge.Strategy.describe(phase)))
            // The ships that exist now get the new phase's jobs; the gate's connections say where the haulers go.
            scope.launch {
                val snapshot = verbs.snapshot()
                val home = snapshot.hqSystem
                val gate = home?.let { h -> snapshot.waypointsIn(h).firstOrNull { it.type == model.system.WaypointType.JUMP_GATE } }
                // The frontier holds gate waypoints, not systems: a jump is to a waypoint, and "X1-ZK21" was refused with 4255 on 2026-09-07.
                val neighbours = gate?.let { g -> runCatching { verbs.jumpGate(g.symbol).connections }.getOrElse { emptyList() } } ?: emptyList()
                runCatching { home?.let { verbs.loadSystem(it) } } // the gate's under-construction flag is stale once it completes
                change("re-plan the fleet for $phase") { knowledge.Strategy.rebalance(phase, it, snapshot, neighbours) }
            }
        }
        shared.onPlanEdited = { edit, why -> change(why, edit) }
    }

    /** Applies a plan the supervisor wrote itself and saves it when it is valid. The edit is made on top of the file's plan, not a stale copy. */
    private fun change(next: Plan, what: String) {
        val problems = apply(next)
        if (problems.isEmpty()) savePlan(next) else logger.warn { "could not $what: $problems" }
    }

    /** Applies [edit] to the latest plan on disk (or the running one), so a hand edit made meanwhile survives. */
    private fun change(what: String, edit: (Plan) -> Plan) {
        val base = loadPlan() ?: plan
        change(edit(base), what)
    }

    /** The gate network as the store remembers it from earlier runs, so routes across several jumps are known from the start. */
    fun seedGates(gates: List<model.responsebody.JumpGate>) { gates.forEach { shared.gates[it.symbol] = it.connections } }

    /** Once a minute: in the escape the gate chains and their teams, in the boom stage transitions per system (docs/boom.md). */
    fun tick() {
        if (plan.phase == Phase.ESCAPE) {
            val snapshot = verbs.snapshot().copy(plan = plan)
            if (knowledge.Strategy.escapeTick(plan, snapshot, clock.now()) != plan) change("the escape's tick: gate chains, surplus") { knowledge.Strategy.escapeTick(it, snapshot.copy(plan = it), clock.now()) }
            return
        }
        if (plan.phase != Phase.BOOM) return
        val snapshot = verbs.snapshot().copy(plan = plan)
        val next = knowledge.Strategy.boomTick(plan, snapshot, clock.now())
        if (next != plan) change("the boom's tick: stages, probes, spread") { knowledge.Strategy.boomTick(it, snapshot.copy(plan = it), clock.now()) }
    }

    /** Applies the plan file when it differs from what is running: `assign` and `phase` in another terminal take effect within seconds. */
    fun reloadIfChanged(): List<String> {
        val onDisk = loadPlan() ?: return emptyList()
        if (onDisk == plan) return emptyList()
        logger.info { "plan.json changed on disk; applying" }
        val problems = apply(onDisk)
        if (problems.isNotEmpty()) logger.warn { "plan.json has problems, keeping the running plan: $problems" }
        return problems
    }

    /** Ships whose behaviour has run to completion since the last change to their assignment. */
    val finished: Set<String> get() = running.filterValues { it.finished }.keys

    val active: Map<String, Assignment> get() = running.filterValues { !it.finished }.mapValues { it.value.assignment }

    /** Starts what is new, stops what is gone, restarts what changed. Returns validation problems, which are not applied. */
    fun apply(plan: Plan): List<String> {
        val problems = plan.validate(verbs.snapshot())
        if (problems.isNotEmpty()) return problems
        val wanted = plan.assignments.associateBy { it.ship }
        running.keys.filterNot { it in wanted }.forEach { stop(it) }
        wanted.values.forEach { a ->
            val current = running[a.ship]
            if (current == null || current.assignment != a) {
                current?.let { stop(a.ship) }
                start(a)
            }
        }
        this.plan = plan
        shared.goals = plan.goals
        shared.plan = plan
        return emptyList()
    }

    fun stopAll() {
        running.keys.toList().forEach { stop(it) }
    }

    private fun start(assignment: Assignment) {
        val spec = Behaviours.get(assignment.behaviour) ?: return
        val entry = Running(assignment)
        running[assignment.ship] = entry
        entry.job = scope.launch {
            var attempt = 0
            while (isActive) {
                val behaviourScope = BehaviourScope(assignment.ship, assignment.behaviour, assignment.params, verbs, shared)
                emit(Event.BehaviourStarted(assignment.ship, assignment.behaviour))
                try {
                    // Every transaction carries the behaviour that made it unless the behaviour tags more precisely.
                    runCatching { verbs.setChain(assignment.ship, assignment.behaviour) }
                    spec.run(behaviourScope)
                    entry.finished = true
                    shared.release(assignment.ship)
                    emit(Event.BehaviourFinished(assignment.ship, assignment.behaviour))
                    // The phase may have a next job for a ship that is done; it is applied from outside this coroutine.
                    val snapshot = verbs.snapshot()
                    val next = snapshot.ships[assignment.ship]?.let { knowledge.Strategy.afterFinished(plan.phase, it, assignment.behaviour, snapshot.copy(plan = plan)) }
                    if (next != null && next != assignment) scope.launch { change("follow ${assignment.behaviour} with ${next.behaviour} on ${assignment.ship}") { it.with(next) } }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempt++
                    val wait = backoff(attempt)
                    val reason = "${e::class.simpleName}: ${e.message} (in phase '${behaviourScope.currentPhase}')"
                    logger.warn(e) { "${assignment.ship} ${assignment.behaviour} failed: $reason; restarting in $wait" }
                    shared.release(assignment.ship)
                    emit(Event.BehaviourFailed(assignment.ship, assignment.behaviour, reason, wait.toString()))
                    runCatching { verbs.setStatus(assignment.ship, engine.ShipStatus(assignment.behaviour, "failed", reason.take(120), clock.now())) }
                    clock.sleep(wait)
                }
            }
        }
    }

    private fun stop(ship: String) {
        val entry = running.remove(ship) ?: return
        entry.job?.cancel()
        shared.release(ship)
        scope.launch { runCatching { verbs.setStatus(ship, null) }; runCatching { verbs.setChain(ship, null) } }
    }

    private fun backoff(attempt: Int): Duration = (10.seconds * (1 shl (attempt - 1).coerceIn(0, 5))).coerceAtMost(5.minutes)

    private class Running(val assignment: Assignment) {
        var job: Job? = null
        @Volatile
        var finished = false
    }
}

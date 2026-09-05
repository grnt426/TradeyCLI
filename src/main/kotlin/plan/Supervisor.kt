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
) {
    private val running = ConcurrentHashMap<String, Running>()

    var plan: Plan = Plan()
        private set

    init {
        shared.onShipPurchased = { ship ->
            val assignment = knowledge.Strategy.defaultAssignment(plan.phase, ship, verbs.snapshot())
            if (assignment != null) change(plan.with(assignment), "assign ${assignment.behaviour} to ${ship.symbol}")
        }
        shared.onPhaseChanged = { phase ->
            logger.info { "phase ${plan.phase} -> $phase" }
            change(plan.withPhase(phase), "move to phase $phase")
            emit(Event.PhaseAdvanced(phase.name, knowledge.Strategy.describe(phase)))
        }
    }

    /** Applies a plan the supervisor wrote itself and saves it when it is valid. */
    private fun change(next: Plan, what: String) {
        val problems = apply(next)
        if (problems.isEmpty()) savePlan(next) else logger.warn { "could not $what: $problems" }
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
                    spec.run(behaviourScope)
                    entry.finished = true
                    shared.release(assignment.ship)
                    emit(Event.BehaviourFinished(assignment.ship, assignment.behaviour))
                    // The phase may have a next job for a ship that is done; it is applied from outside this coroutine.
                    val snapshot = verbs.snapshot()
                    val next = snapshot.ships[assignment.ship]?.let { knowledge.Strategy.afterFinished(plan.phase, it, assignment.behaviour, snapshot.copy(plan = plan)) }
                    if (next != null && next != assignment) scope.launch { change(plan.with(next), "follow ${assignment.behaviour} with ${next.behaviour} on ${assignment.ship}") }
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
        scope.launch { runCatching { verbs.setStatus(ship, null) } }
    }

    private fun backoff(attempt: Int): Duration = (10.seconds * (1 shl (attempt - 1).coerceIn(0, 5))).coerceAtMost(5.minutes)

    private class Running(val assignment: Assignment) {
        var job: Job? = null
        @Volatile
        var finished = false
    }
}

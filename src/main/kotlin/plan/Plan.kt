package plan

import behaviour.Behaviours
import engine.Snapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The only runtime-editable piece of automation: which ship runs which behaviour with which
 * parameters. Lives in `profile/agents/<SYMBOL>/plan.json`; `assign`, `unassign` and `plan` in
 * line mode edit it.
 */
@Serializable
data class Plan(
    val assignments: List<Assignment> = emptyList(),
    val goals: Goals = Goals(),
) {
    fun with(assignment: Assignment): Plan = copy(assignments = assignments.filterNot { it.ship == assignment.ship } + assignment)
    fun without(ship: String): Plan = copy(assignments = assignments.filterNot { it.ship == ship })
    fun assignmentFor(ship: String): Assignment? = assignments.firstOrNull { it.ship == ship }
    fun withGoal(goal: FleetGoal): Plan = copy(goals = goals.copy(fleet = goals.fleet.filterNot { it.type == goal.type } + goal))
    fun withoutGoal(type: model.ship.ShipType): Plan = copy(goals = goals.copy(fleet = goals.fleet.filterNot { it.type == type }))

    /** Problems that would stop the plan from running, one line each. Empty means it is fine. */
    fun validate(snapshot: Snapshot): List<String> = assignments.flatMap { a ->
        val spec = Behaviours.get(a.behaviour) ?: return@flatMap listOf("${a.ship}: unknown behaviour '${a.behaviour}'")
        val ship = snapshot.ships[a.ship] ?: return@flatMap listOf("${a.ship}: no such ship")
        val unknown = a.params.keys - spec.params.map { it.name }.toSet()
        val missing = spec.params.filter { it.required && it.name !in a.params }.map { it.name }
        buildList {
            unknown.forEach { add("${a.ship}: ${a.behaviour} has no parameter '$it'") }
            missing.forEach { add("${a.ship}: ${a.behaviour} needs --$it") }
            if (isEmpty()) addAll(spec.validate(snapshot, ship, a.params).map { "${a.ship}: $it" })
        }
    }

    companion object {
        private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

        fun load(file: File): Plan = if (file.isFile) json.decodeFromString(file.readText()) else Plan()

        fun save(file: File, plan: Plan) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(plan))
        }
    }
}

@Serializable
data class Assignment(
    val ship: String,
    val behaviour: String,
    val params: Map<String, String> = emptyMap(),
) {
    fun describe(): String = "$behaviour" + params.entries.joinToString("") { (k, v) -> " --$k $v" }
}

@Serializable
data class Goals(
    /** Informational: the credits target for the reset. */
    val credits: Long? = null,
    /** Ships to buy as money allows. A trader docked at a shipyard that lists the type buys one when the bank stays above the reserve. */
    val fleet: List<FleetGoal> = emptyList(),
)

@Serializable
data class FleetGoal(
    val type: model.ship.ShipType,
    /** How many of this type the fleet should end up with, counting the ones it has. */
    val count: Int,
    /** Credits that must remain after the purchase. */
    val reserve: Long = 100_000,
)

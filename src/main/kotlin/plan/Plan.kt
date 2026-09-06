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
    /** Teams of worker bees feeding a production chain; see `behaviour.decisions.Chains`. */
    val chains: List<Chain> = emptyList(),
    /** Which stage of the reset the agent is in; `knowledge.Strategy` turns it into weights and default jobs. */
    val phase: Phase = Phase.ESCAPE,
    /** Set once the gate is comfortably affordable: traders are called to it and new haulers go straight there. */
    val rushing: Boolean = false,
    /** One record per system the fleet has entered in the boom (docs/boom.md). */
    val systems: Map<String, SystemRecord> = emptyMap(),
    /** Gates we know of and have not entered, each with the system whose gate leads to it. */
    val frontier: List<FrontierGate> = emptyList(),
) {
    fun withPhase(phase: Phase): Plan = copy(phase = phase)
    fun withSystem(record: SystemRecord): Plan = copy(systems = systems + (record.symbol to record))
    fun system(symbol: String): SystemRecord? = systems[symbol]
    fun withFrontier(gates: List<FrontierGate>): Plan = copy(frontier = (frontier + gates).distinctBy { it.gate }.filter { it.gate.substringBeforeLast('-') !in systems })
    fun withoutFrontier(gate: String): Plan = copy(frontier = frontier.filterNot { it.gate == gate })
    fun withChain(chain: Chain): Plan = copy(chains = chains.filterNot { it.id == chain.id } + chain)
    fun withoutChain(id: String): Plan = copy(chains = chains.filterNot { it.id == id })
    fun chain(id: String): Chain? = chains.firstOrNull { it.id == id }
    fun with(assignment: Assignment): Plan = copy(assignments = assignments.filterNot { it.ship == assignment.ship } + assignment)
    fun without(ship: String): Plan = copy(assignments = assignments.filterNot { it.ship == ship })
    fun assignmentFor(ship: String): Assignment? = assignments.firstOrNull { it.ship == ship }
    fun withGoal(goal: FleetGoal): Plan = copy(goals = goals.copy(fleet = goals.fleet.filterNot { it.type == goal.type && it.system == goal.system } + goal))
    fun withoutGoal(type: model.ship.ShipType, system: String? = null): Plan = copy(goals = goals.copy(fleet = goals.fleet.filterNot { it.type == type && it.system == system }))

    /** Problems that would stop the plan from running, one line each. Empty means it is fine. */
    fun validate(snapshot: Snapshot): List<String> = assignments.flatMap { a ->
        val spec = Behaviours.get(a.behaviour) ?: return@flatMap listOf("${a.ship}: unknown behaviour '${a.behaviour}'")
        val ship = snapshot.ships[a.ship] ?: return@flatMap listOf("${a.ship}: no such ship")
        val unknown = a.params.keys - spec.params.map { it.name }.toSet()
        val missing = spec.params.filter { it.required && it.name !in a.params }.map { it.name }
        buildList {
            unknown.forEach { add("${a.ship}: ${a.behaviour} has no parameter '$it'") }
            missing.forEach { add("${a.ship}: ${a.behaviour} needs --$it") }
            if (isEmpty()) addAll(spec.validate(snapshot.copy(plan = this@Plan), ship, a.params).map { "${a.ship}: $it" })
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

/** The stages of a reset (docs/phases.md): escape the home system, boom across fresh ones, then settle. */
@Serializable
enum class Phase { ESCAPE, BOOM, LATE }

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

/** A leg of a chain: haul [good] from [from] and sell it at [to]. */
@Serializable
data class Leg(val good: model.market.TradeSymbol, val from: String, val to: String) {
    init { require(from != to) { "a leg needs two different markets, got $from twice" } }
    override fun toString(): String = "$good $from/$to"
}

/**
 * A production chain worked by a team. Legs are run whether or not they pay; the ledger judges the
 * whole. [baselines] are each ship's free-agent credits per hour when it was enrolled, the fixed
 * counterfactual the release policy compares against. [hold] makes the policy advisory only.
 */
@Serializable
data class Chain(
    val id: String,
    val legs: List<Leg>,
    val ships: List<String> = emptyList(),
    val baselines: Map<String, Double> = emptyMap(),
    val enrolledAt: String,
    val hold: Boolean = true,
    val lastReleaseAt: String? = null,
    val note: String = "",
    /** Bees never spend the bank below this: a chain that loses money must not bankrupt the agent. */
    val reserve: Long = 200_000,
) {
    val enrolled: java.time.Instant get() = java.time.Instant.parse(enrolledAt)
    val lastRelease: java.time.Instant? get() = lastReleaseAt?.let { java.time.Instant.parse(it) }
}

@Serializable
data class FleetGoal(
    val type: model.ship.ShipType,
    /** How many of this type the fleet should end up with, counting the ones it has. */
    val count: Int,
    /** Credits that must remain after the purchase. */
    val reserve: Long = 100_000,
    /** The system this goal is for: only ships there count, and the ship is bought for it (null: anywhere). */
    val system: String? = null,
) {
    fun owned(ships: Collection<model.ship.Ship>): Int = ships.count { behaviour.BehaviourScope.shipTypeOf(it) == type && (system == null || it.nav.systemSymbol == system) }
    fun describe(): String = "${count}x${type.name.removePrefix("SHIP_")}" + (system?.let { " in $it" } ?: "")
}

/** Where a system stands in the boom (docs/boom.md). */
@Serializable
enum class Stage { CASCADE, RUSH, NETWORK, SETTLE }

@Serializable
data class SystemRecord(
    val symbol: String,
    val stage: Stage = Stage.CASCADE,
    val gate: String? = null,
    val gateBuilt: Boolean = false,
    /** The shipyard the rush buys at; null when the system has none and its kit is seeded from a neighbour. */
    val yard: String? = null,
    val pioneer: String? = null,
    val arrivedAt: String? = null,
    /** Credits the rush may spend on this system's kit. */
    val budget: Long = 350_000,
    /** Credits spent on ships for this system. */
    val spent: Long = 0,
    val note: String = "",
)

/** A gate we know of and have not entered, and the system whose gate leads to it. */
@Serializable
data class FrontierGate(val gate: String, val via: String) {
    val system: String get() = gate.substringBeforeLast('-')
}

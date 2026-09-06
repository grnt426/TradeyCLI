package behaviour

import behaviour.decisions.Intentions
import behaviour.decisions.Tour
import engine.VerbFailure
import knowledge.Strategy
import model.WaypointTraitSymbol
import model.ship.ShipType
import model.system.WaypointType
import plan.FleetGoal
import plan.FrontierGate
import plan.Stage
import plan.SystemRecord
import kotlin.time.Duration.Companion.minutes

/**
 * The pioneer (docs/boom.md): a probe that takes the next gate on the frontier, reads the gate on
 * the far side for more gates, finds the system's shipyard and buys its rush kit there, and moves
 * on. Where the system has no yard it charts on the spot instead, because charting is the money
 * that funds the boom, and the kit is bought for it at a neighbour's yard. Every delay between
 * arrival and the first chart is credits lost, so the pioneer does nothing else.
 */
val pioneerSpec = BehaviourSpec(
    name = "pioneer",
    description = "Take the next frontier gate, read the far gate for more, buy the system's rush kit at its yard (or chart it if it has none), move on.",
    params = listOf(ParamSpec("kit", "Credits the rush may spend per system (default 350000)")),
    validate = { _, ship, _ -> buildList { if (ship.usesFuel) add("${ship.symbol} burns fuel; pioneers are probes") } },
    run = { pioneer() },
)

suspend fun BehaviourScope.pioneer() {
    val kit = param("kit")?.toLongOrNull() ?: Strategy.RUSH_KIT
    while (true) {
        clock.sleep(1.minutes.div(6))
        val plan = shared.plan
        val here = me.nav.systemSymbol
        // The nearest frontier gate nobody else has claimed: reachable from where the ship stands first.
        val entry = plan.frontier
            .filter { it.gate !in shared.unreachableGates && !shared.claimedByOther(it.gate, ship) }
            .sortedBy { if (it.via == here) 0 else 1 }
            .firstOrNull()
        if (entry == null) {
            // Nothing to enter: be useful where the ship stands.
            if (snapshot().waypointsIn(here).any { it.hasTrait(WaypointTraitSymbol.UNCHARTED) }) { phase("chart while waiting", here) { chartSystem() }; continue }
            status("waiting", "the frontier is empty from $here; checking again in 10 minutes")
            clock.sleep(10.minutes)
            continue
        }
        shared.claim(ship, entry.gate)
        if (here != entry.via && !goToSystem(entry.via)) { shared.release(ship); continue }
        val fromGate = snapshot().waypointsIn(me.nav.systemSymbol).firstOrNull { it.type == WaypointType.JUMP_GATE } ?: throw BehaviourFailure("${me.nav.systemSymbol} has no gate")
        phase("travel to gate", fromGate.symbol) { travelTo(fromGate.symbol) }
        val arrived = phase("jump", "to ${entry.gate}") {
            try {
                jump(ship, entry.gate); true
            } catch (e: VerbFailure.Api) {
                if (e.error.code != DESTINATION_UNDER_CONSTRUCTION) throw e
                shared.unreachableGates += entry.gate
                shared.editPlan("frontier: ${entry.gate} is unbuilt") { it.withoutFrontier(entry.gate) }
                status(detail = "${entry.gate} is under construction; dropped from the frontier")
                false
            }
        }
        shared.release(ship)
        if (!arrived) continue
        val system = entry.system
        val arrivedAt = clock.now().toString()
        shared.editPlan("entered $system") { p -> p.withoutFrontier(entry.gate).withSystem(SystemRecord(system, Stage.CASCADE, gate = entry.gate, gateBuilt = true, pioneer = ship, arrivedAt = arrivedAt, budget = kit)) }
        if (snapshot().waypointsIn(system).isEmpty()) phase("map", system) { loadSystem(system) }
        // Cascade: the gate on this side leads on to gates we may not know.
        phase("read gate", entry.gate) {
            val known = shared.plan.systems.keys + shared.plan.frontier.map { it.system } + system
            val more = jumpGate(entry.gate).connections.filter { it.substringBeforeLast('-') !in known && it !in shared.unreachableGates }.map { FrontierGate(it, system) }
            if (more.isNotEmpty()) shared.editPlan("frontier from $system: ${more.size} more") { it.withFrontier(more) }
            status(detail = "${more.size} new gate(s) beyond $system; frontier ${shared.plan.frontier.size}")
        }
        // Rush: the kit, bought here if there is a yard, else for here at a neighbour's yard while this probe charts.
        val yard = Tour.nearest(this.here, snapshot().waypointsIn(system).filter { it.hasShipyard })
        val goals = listOf(FleetGoal(ShipType.SHIP_PROBE, 2, reserve = Strategy.GALAXY_RESERVE, system = system), FleetGoal(ShipType.SHIP_LIGHT_HAULER, 1, reserve = Strategy.GALAXY_RESERVE, system = system))
        shared.editPlan("rush kit for $system") { p -> goals.fold(p) { acc, g -> acc.withGoal(g) } }
        if (yard == null) {
            shared.editPlan("$system has no yard") { p -> p.withSystem(p.system(system)!!.copy(stage = Stage.RUSH, yard = null, note = "no shipyard; kit bought at a neighbour")) }
            status(detail = "$system has no shipyard; charting it now while the kit comes from a neighbour")
            phase("chart", system) { chartSystem() }
            continue
        }
        phase("travel to yard", yard.symbol) { travelTo(yard.symbol) }
        phase("rush", "buying the kit at ${yard.symbol}") {
            dock(ship)
            refreshShipyard(yard.symbol)
            var bought = 0
            var spent = 0L
            val start = agent().credits
            while (spent < kit) {
                val before = agent().credits
                val bought1 = maybeExpand() ?: break
                spent += before - agent().credits
                bought++
                status(detail = "bought ${bought1.symbol} for $system; ${Intentions.format(spent)} of the ${Intentions.format(kit)} kit")
            }
            shared.editPlan("$system rushed") { p -> p.withSystem(p.system(system)!!.copy(stage = Stage.RUSH, yard = yard.symbol, spent = start - agent().credits)) }
            status(detail = "$bought ship(s) bought for $system at ${yard.symbol}; the rest of the kit as the bank allows")
        }
    }
}

package knowledge

import behaviour.BehaviourScope
import behaviour.Behaviours
import behaviour.decisions.MiningAssumptions
import behaviour.decisions.TradingAssumptions
import engine.Snapshot
import model.market.ActivityLevel.GROWING
import model.market.ActivityLevel.RESTRICTED
import model.market.ActivityLevel.STRONG
import model.market.ActivityLevel.WEAK
import model.market.SupplyLevel.ABUNDANT
import model.market.SupplyLevel.HIGH
import model.market.SupplyLevel.LIMITED
import model.market.SupplyLevel.MODERATE
import model.market.SupplyLevel.SCARCE
import model.ship.Ship
import model.ship.ShipType
import plan.Assignment
import plan.FleetGoal
import plan.Goals
import plan.Phase
import plan.Plan

/**
 * The three phases of a reset and what each one changes, in one place so the plan's `phase` is
 * the only switch (docs/phases.md).
 *
 * ESCAPE: the home gate is unfinished. Market health comes before profit: the ranking's health
 * weights are strict, mining sells where the sale helps a starved importer, the gate hauler nurses
 * producers, and a bought hauler goes straight to the gate. BOOM: the gate is open. Probes explore
 * and chart, traders drain fresh systems at a lower margin floor. LATE: stable. Profit first with
 * soft health weights, and the weights are loosened on purpose to measure where a market tips.
 */
object Strategy {

    /** Haulers on the gate once finishing is comfortable; the rest of the escape fleet stays on health and income. */
    const val RUSH_HAULERS = 3
    /** The bank must cover this many times the remaining bill at today's prices, plus [POST_GATE_RESERVE], before the rush starts: prices climb as we buy. */
    const val RUSH_COMFORT = 1.5
    /** Credits kept back through the rush so the boom starts with working capital and a hull or two. */
    const val POST_GATE_RESERVE = 500_000L

    fun comfortableBank(remainingCost: Long): Long = (remainingCost * RUSH_COMFORT).toLong() + POST_GATE_RESERVE

    /** Whether the gate is close enough to finish that more haulers should be bought for it, without touching the post-gate reserve. */
    fun gateRush(bank: Long, remainingCost: Long): Boolean = remainingCost > 0 && bank >= comfortableBank(remainingCost)

    /** What the site still needs at the cheapest price each material shows in the home system, or null when a material has no price. */
    fun remainingCost(bill: List<model.ConstructionMaterial>, snapshot: Snapshot): Long? {
        val home = snapshot.hqSystem ?: return null
        var total = 0L
        for (m in bill) {
            val left = m.required - m.fulfilled
            if (left <= 0) continue
            val cheapest = snapshot.marketsIn(home).mapNotNull { it.good(m.tradeSymbol)?.purchasePrice }.minOrNull() ?: return null
            total += left * cheapest
        }
        return total
    }

    fun market(phase: Phase): MarketAssumptions = when (phase) {
        Phase.ESCAPE -> MarketAssumptions()
        Phase.BOOM -> MarketAssumptions(
            destinationSupplyWeight = mapOf(SCARCE to 1.0, LIMITED to 1.0, MODERATE to 0.9, HIGH to 0.7, ABUNDANT to 0.0),
            importFeedBonus = mapOf(SCARCE to 1.3, LIMITED to 1.1, MODERATE to 1.0, HIGH to 1.0, ABUNDANT to 1.0),
        )
        Phase.LATE -> MarketAssumptions(
            sourceSupplyWeight = mapOf(SCARCE to 0.2, LIMITED to 0.7, MODERATE to 1.0, HIGH to 1.0, ABUNDANT to 1.0),
            sourceActivityWeight = mapOf(RESTRICTED to 0.5, WEAK to 0.8, GROWING to 1.0, STRONG to 1.0),
            destinationSupplyWeight = mapOf(SCARCE to 1.0, LIMITED to 1.0, MODERATE to 1.0, HIGH to 0.8, ABUNDANT to 0.3),
            importFeedBonus = mapOf(SCARCE to 1.0, LIMITED to 1.0, MODERATE to 1.0, HIGH to 1.0, ABUNDANT to 1.0),
            restrictedTakeFloor = LIMITED,
        )
    }

    /** The margin below which a route is not worth running; ESCAPE leaves more on the table to keep routes alive. */
    fun marginFloor(phase: Phase): Double = when (phase) {
        Phase.ESCAPE -> 0.15
        Phase.BOOM -> 0.10
        Phase.LATE -> 0.10
    }

    fun trading(phase: Phase, minMarginPerUnit: Int? = null, minMarginRatio: Double? = null): TradingAssumptions = TradingAssumptions(
        minMarginPerUnit = minMarginPerUnit ?: 20,
        minMarginRatio = minMarginRatio ?: marginFloor(phase),
        market = market(phase),
    )

    /** Mining sells where the sale helps most in ESCAPE; later phases weigh the sale softly and only skip saturated buyers. */
    fun mining(phase: Phase): MiningAssumptions = MiningAssumptions(market = market(phase))

    /** What a new agent should want in each phase, as fleet goals. */
    fun goals(phase: Phase): Goals = when (phase) {
        Phase.ESCAPE -> Goals(fleet = listOf(
            FleetGoal(ShipType.SHIP_LIGHT_SHUTTLE, 2, reserve = 120_000),
            FleetGoal(ShipType.SHIP_MINING_DRONE, 2, reserve = 150_000),
            FleetGoal(ShipType.SHIP_LIGHT_HAULER, 1, reserve = 250_000),
        ))
        Phase.BOOM -> Goals(fleet = listOf(FleetGoal(ShipType.SHIP_LIGHT_HAULER, 2, reserve = 300_000), FleetGoal(ShipType.SHIP_PROBE, 2, reserve = 100_000)))
        Phase.LATE -> Goals()
    }

    /**
     * The markets a spare probe should sit at and re-read every few minutes, most useful first:
     * the producers of what the home site still needs, then the producers of their starved inputs.
     * Fresh readings are what keep the haulers' rate honest; a stale LIMITED parked three haulers
     * for four hours on 2026-09-05.
     */
    fun watchMarkets(snapshot: Snapshot): List<String> {
        val home = snapshot.hqSystem ?: return emptyList()
        val markets = snapshot.marketsIn(home)
        val wanted = snapshot.constructionBill?.filter { it.fulfilled < it.required }?.map { it.tradeSymbol } ?: emptyList()
        val out = linkedSetOf<String>()
        wanted.forEach { good -> markets.filter { it.typeOf(good) == model.market.TradeGoodType.EXPORT }.forEach { out += it.symbol } }
        wanted.forEach { good ->
            ImportMap.inputs[good].orEmpty().forEach { input -> markets.filter { it.typeOf(input) == model.market.TradeGoodType.EXPORT }.forEach { out += it.symbol } }
        }
        return out.toList()
    }

    /** The job a ship gets when nobody said otherwise: a bought ship, or a fresh agent's plan. */
    fun defaultAssignment(phase: Phase, ship: Ship, snapshot: Snapshot): Assignment? {
        val home = snapshot.hqSystem
        val site = home?.let { h -> snapshot.waypointsIn(h).firstOrNull { it.isUnderConstruction } }
        val isHauler = ship.usesFuel && ship.cargo.capacity >= 60 && !ship.canMine
        return when (phase) {
            Phase.ESCAPE -> when {
                isHauler && site != null && ship.nav.systemSymbol == home -> Assignment(ship.symbol, "supplyGate", mapOf("site" to site.symbol, "reserve" to "200000"))
                // A second probe watches the producers that matter; the first one roams (and buys the fleet).
                !ship.usesFuel && snapshot.plan?.assignments?.any { it.behaviour == "probeMarkets" || it.behaviour == "expand" } == true -> {
                    val watched = snapshot.plan.assignments.mapNotNull { it.params["markets"] }.flatMap { it.split(',') }.toSet()
                    val next = watchMarkets(snapshot).firstOrNull { it !in watched }
                    if (next != null) Assignment(ship.symbol, "probeMarkets", mapOf("markets" to next, "maxAge" to "5"))
                    else Assignment(ship.symbol, "probeMarkets", mapOf("maxAge" to "10"))
                }
                else -> Behaviours.defaultFor(ship)?.let { Assignment(ship.symbol, it) }
            }
            Phase.BOOM -> when {
                !ship.usesFuel -> Assignment(ship.symbol, "explore", mapOf("maxSystems" to "10"))
                else -> Behaviours.defaultFor(ship)?.let { Assignment(ship.symbol, it) }
            }
            Phase.LATE -> Behaviours.defaultFor(ship)?.let { Assignment(ship.symbol, it) }
        }
    }

    /**
     * The plan for the ships that already exist when the phase changes. BOOM: the first probe keeps
     * reading the home system's prices, every other probe explores, and each hauler takes a
     * different neighbouring system to trade in; the rest keep their jobs.
     */
    fun rebalance(phase: Phase, plan: Plan, snapshot: Snapshot, neighbours: List<String>): Plan {
        if (phase != Phase.BOOM) return plan
        var next = plan
        val ships = snapshot.ships.values.sortedBy { it.symbol }
        val probes = ships.filter { !it.usesFuel }
        probes.forEachIndexed { i, probe ->
            next = next.with(if (i == 0) Assignment(probe.symbol, "probeMarkets", mapOf("maxAge" to "10")) else Assignment(probe.symbol, "explore", mapOf("maxSystems" to "10")))
        }
        val haulers = ships.filter { it.usesFuel && it.cargo.capacity >= 60 && !it.canMine }
        haulers.forEachIndexed { i, hauler ->
            val target = neighbours.getOrNull(i % neighbours.size.coerceAtLeast(1))
            next = next.with(if (target != null) Assignment(hauler.symbol, "trade", mapOf("system" to target)) else Assignment(hauler.symbol, "trade"))
        }
        return next
    }

    /** What a ship does once its behaviour has run to completion; null leaves it finished. */
    fun afterFinished(phase: Phase, ship: Ship, behaviour: String, snapshot: Snapshot): Assignment? = when {
        // The gate is done: its haulers become the boom's traders.
        behaviour == "supplyGate" && ship.cargo.capacity > 0 -> Assignment(ship.symbol, "trade")
        // The probe has read every market: park it at a yard and let it buy the fleet the goals ask for.
        !ship.usesFuel && behaviour == "probeMarkets" && (snapshot.plan?.goals?.fleet?.isNotEmpty() == true) -> Assignment(ship.symbol, "expand")
        // An explorer that ran out of map goes back to watching prices where it stands.
        !ship.usesFuel && behaviour == "explore" -> Assignment(ship.symbol, "probeMarkets")
        else -> null
    }

    fun describe(phase: Phase): String = when (phase) {
        Phase.ESCAPE -> "ESCAPE: market health first; profits fund the logistics that keep producers fed and the gate supplied"
        Phase.BOOM -> "BOOM: the gate is open; probes explore and chart, traders drain fresh systems"
        Phase.LATE -> "LATE: profit first with soft health weights; measuring where markets tip"
    }

    /** The type a ship counts as for fleet goals, for callers without a scope. */
    fun typeOf(ship: Ship): ShipType? = BehaviourScope.shipTypeOf(ship)
}

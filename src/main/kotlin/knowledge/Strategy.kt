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
import plan.SystemRecord
import plan.Stage
import plan.FrontierGate

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
    /**
     * Haulers in ESCAPE that do nothing but feed the gate's producers (`feed` on the gate chains).
     * The clock on the escape is the producers' output, not money: on 2026-09-06 F55 made about
     * 40 FAB_MATS an hour with its iron flickering LIMITED, the bank sat on 1.5M it could not
     * spend, and the trade routes were down to two. Fed inputs turn WEAK production GROWING and
     * then STRONG, and trade volume grows with it.
     */
    const val FEEDERS = 2
    /** Haulers in ESCAPE with a job of their own before any feeder: the gate hauler, the contract hauler, a trader. */
    const val ESCAPE_CREW = 3
    /** The bank must cover this many times the remaining bill at today's prices, plus [POST_GATE_RESERVE], before the rush starts. Nursed producers held or lowered their prices through the whole bill on 2026-09-05, so the margin is small. */
    const val RUSH_COMFORT = 1.25
    /** Credits kept back through the rush so the boom starts with working capital and a hull or two. */
    const val POST_GATE_RESERVE = 500_000L

    fun comfortableBank(remainingCost: Long): Long = (remainingCost * RUSH_COMFORT).toLong() + POST_GATE_RESERVE

    /** Credits each trading ship needs in hand to fill a hold: a 40-unit load of clothing or fabrics costs 70k. Three haulers bought in one minute on 2026-09-06 left 90k for five traders. */
    const val WORKING_CAPITAL_PER_TRADER = 120_000L
    /** Minutes between ship purchases, so each one's effect on income is seen before the next. */
    const val MINUTES_BETWEEN_PURCHASES = 10L

    /** The bank a purchase must leave: the goal's reserve, or enough working capital for every ship that trades, whichever is more. */
    fun purchaseReserve(goalReserve: Long, snapshot: Snapshot): Long {
        val traders = snapshot.ships.values.count { it.usesFuel && it.cargo.capacity >= 40 } + 1 // plus the one being bought
        return maxOf(goalReserve, traders * WORKING_CAPITAL_PER_TRADER)
    }

    /** The boom (docs/boom.md): what a system's rush may spend, the bank floor no rush goes below, how many pioneers roam, and the share of the bank a far gate may draw. */
    const val RUSH_KIT = 350_000L
    const val GALAXY_RESERVE = 300_000L
    const val PIONEERS = 2
    const val NETWORK_SHARE = 0.25
    /** Ships a settled system keeps: two haulers trading and gardening, one probe watching prices. */
    const val SETTLE_HAULERS = 2
    const val SETTLE_PROBES = 1

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

    fun trading(phase: Phase, minMarginPerUnit: Int? = null, minMarginRatio: Double? = null, snapshot: Snapshot? = null): TradingAssumptions = TradingAssumptions(
        minMarginPerUnit = minMarginPerUnit ?: 20,
        minMarginRatio = minMarginRatio ?: marginFloor(phase),
        market = market(phase),
        chainTargets = if (phase == Phase.ESCAPE && snapshot != null) chainTargets(snapshot) else emptySet(),
        protectedSources = if (phase == Phase.ESCAPE && snapshot != null) protectedChainSources(snapshot) else emptySet(),
        chainSources = if (phase == Phase.ESCAPE && snapshot != null) chainSources(snapshot) else emptySet(),
    )

    const val GATE_CHAIN = "gate-"

    /**
     * One chain per material the gate still needs: every input its home producer imports, hauled
     * from the cheapest other market that lists it, and the same again for the inputs' own
     * producers in the system (A3's copper and silicon behind D47's microprocessors). Legs run
     * whether or not they pay; the chain's ledger judges them (`chain` in line mode).
     */
    fun gateChains(snapshot: Snapshot, now: java.time.Instant): List<plan.Chain> {
        val home = snapshot.hqSystem ?: return emptyList()
        val roots = snapshot.constructionBill?.filter { it.fulfilled < it.required }?.map { it.tradeSymbol } ?: return emptyList()
        val markets = snapshot.marketsIn(home)
        fun producerOf(good: model.market.TradeSymbol) = markets.filter { it.typeOf(good) == model.market.TradeGoodType.EXPORT }.minByOrNull { it.good(good)?.purchasePrice ?: Int.MAX_VALUE }
        // The cheapest source, unless one within a tenth of its price is nearer: quartz at B7 and H59 both cost 22, and H59 is 200 closer to the fab.
        fun sourceOf(input: model.market.TradeSymbol, to: model.market.Market): model.market.Market? {
            val candidates = markets.filter { it.symbol != to.symbol && it.typeOf(input).let { t -> t == model.market.TradeGoodType.EXPORT || t == model.market.TradeGoodType.EXCHANGE } }
            val cheapest = candidates.minOfOrNull { it.good(input)?.purchasePrice ?: Int.MAX_VALUE } ?: return null
            val there = snapshot.waypoints[to.symbol]
            return candidates.filter { (it.good(input)?.purchasePrice ?: Int.MAX_VALUE) <= cheapest * 1.1 }
                .minByOrNull { m -> val w = snapshot.waypoints[m.symbol]; if (w == null || there == null) Double.MAX_VALUE else engine.Travel.distance(w.x, w.y, there.x, there.y) }
        }
        return roots.mapNotNull { root ->
            val producer = producerOf(root) ?: return@mapNotNull null
            val legs = linkedSetOf<plan.Leg>()
            fun feed(p: model.market.Market, good: model.market.TradeSymbol, depth: Int) {
                ImportMap.inputsOf(good, p.imports.map { it.symbol }).forEach { input ->
                    val source = sourceOf(input, p) ?: return@forEach
                    legs += plan.Leg(input, source.symbol, p.symbol)
                    if (depth < 1 && source.typeOf(input) == model.market.TradeGoodType.EXPORT) feed(source, input, depth + 1)
                }
            }
            feed(producer, root, 0)
            if (legs.isEmpty()) null
            else plan.Chain("$GATE_CHAIN${root.name}", legs.toList(), enrolledAt = now.toString(), note = "feeds ${producer.symbol}, the ${root.name} producer, so its output grows")
        }
    }

    /**
     * The escape's bookkeeping, once a minute: the gate chains exist while the bill is unpaid, and
     * every ship assigned to feed one is on its team (so the legs rotate over the team). Chains
     * already in the plan, by hand or earlier, are left as they are. Pure.
     */
    fun seedGateChains(plan: Plan, snapshot: Snapshot, now: java.time.Instant): Plan {
        var next = plan
        gateChains(snapshot, now).forEach { chain -> if (plan.chain(chain.id) == null) next = next.withChain(chain) }
        next.assignments.filter { it.behaviour == "feed" }.forEach { a ->
            val chain = next.chain(a.params["chain"] ?: return@forEach) ?: return@forEach
            if (a.ship !in chain.ships) next = next.withChain(chain.copy(ships = chain.ships + a.ship))
        }
        return next
    }

    /** "market/good" for every export of a gate-chain good in the home system, healthy or not. */
    fun chainSources(snapshot: Snapshot): Set<String> {
        val home = snapshot.hqSystem ?: return emptySet()
        val roots = snapshot.constructionBill?.filter { it.fulfilled < it.required }?.map { it.tradeSymbol } ?: return emptySet()
        val goods = linkedSetOf<model.market.TradeSymbol>()
        fun walk(g: model.market.TradeSymbol, depth: Int) { if (goods.add(g) && depth < 4) ImportMap.inputs[g].orEmpty().forEach { walk(it, depth + 1) } }
        roots.forEach { walk(it, 0) }
        return snapshot.marketsIn(home).flatMap { m -> goods.filter { m.typeOf(it) == model.market.TradeGoodType.EXPORT }.map { "${m.symbol}/${it.name}" } }.toSet()
    }

    /** "market/good" for every export of a gate-chain producer that has a LIMITED-or-worse input: draining it only raises the bill. */
    fun protectedChainSources(snapshot: Snapshot): Set<String> {
        val home = snapshot.hqSystem ?: return emptySet()
        val roots = snapshot.constructionBill?.filter { it.fulfilled < it.required }?.map { it.tradeSymbol } ?: return emptySet()
        val goods = linkedSetOf<model.market.TradeSymbol>()
        fun walk(g: model.market.TradeSymbol, depth: Int) { if (goods.add(g) && depth < 4) ImportMap.inputs[g].orEmpty().forEach { walk(it, depth + 1) } }
        roots.forEach { walk(it, 0) }
        val out = mutableSetOf<String>()
        snapshot.marketsIn(home).forEach { m ->
            goods.forEach { g ->
                if (m.typeOf(g) == model.market.TradeGoodType.EXPORT && MarketHealth.starvedInputs(m, g).any { it.supply <= model.market.SupplyLevel.LIMITED }) out += "${m.symbol}/${g.name}"
            }
        }
        return out
    }

    /** "market/good" for every LIMITED-or-worse input of a producer in the gate's chains, down to the ores. */
    fun chainTargets(snapshot: Snapshot): Set<String> {
        val home = snapshot.hqSystem ?: return emptySet()
        val roots = snapshot.constructionBill?.filter { it.fulfilled < it.required }?.map { it.tradeSymbol } ?: return emptySet()
        val goods = linkedSetOf<model.market.TradeSymbol>()
        fun walk(g: model.market.TradeSymbol, depth: Int) { if (goods.add(g) && depth < 4) ImportMap.inputs[g].orEmpty().forEach { walk(it, depth + 1) } }
        roots.forEach { walk(it, 0) }
        val out = mutableSetOf<String>()
        snapshot.marketsIn(home).forEach { m ->
            goods.forEach { g ->
                if (m.typeOf(g) == model.market.TradeGoodType.EXPORT) {
                    MarketHealth.starvedInputs(m, g).filter { it.supply <= model.market.SupplyLevel.LIMITED }.forEach { out += "${m.symbol}/${it.symbol.name}" }
                }
            }
        }
        return out
    }

    /** Mining sells where the sale helps most in ESCAPE; later phases weigh the sale softly and only skip saturated buyers. */
    fun mining(phase: Phase): MiningAssumptions = MiningAssumptions(market = market(phase))

    /** A mining drone's tank (80) cruises both ways only when a rock lies within this of a market that buys its ore. */
    const val DRONE_REACH = 35.0

    /** Whether this system has a rock a drone can work without drifting home. */
    fun dronesWorthIt(snapshot: Snapshot): Boolean {
        val home = snapshot.hqSystem ?: return false
        val markets = snapshot.waypointsIn(home).filter { it.hasMarket }
        return snapshot.asteroidsIn(home).any { rock -> !rock.isSiphonable && markets.any { m -> engine.Travel.distance(rock.x, rock.y, m.x, m.y) <= DRONE_REACH } }
    }

    /** [goals] adjusted to the system: no drones where every rock is a drift away from a buyer. */
    fun goals(phase: Phase, snapshot: Snapshot): Goals {
        val base = goals(phase)
        return if (phase == Phase.ESCAPE && !dronesWorthIt(snapshot)) base.copy(fleet = base.fleet.filter { it.type != ShipType.SHIP_MINING_DRONE }) else base
    }

    /** What a new agent should want in each phase, as fleet goals. */
    fun goals(phase: Phase): Goals = when (phase) {
        // Light shuttles are a trap: a small hold, no faster, less fuel, not much cheaper. Haulers, and drones for ore.
        // Haulers first: on 2026-09-06 a fresh system paid a trader ~500k an hour, so a 273k hauler earns itself back in about an hour.
        Phase.ESCAPE -> Goals(fleet = listOf(
            FleetGoal(ShipType.SHIP_LIGHT_HAULER, 5 + FEEDERS, reserve = 200_000),
            FleetGoal(ShipType.SHIP_SURVEYOR, 2, reserve = 100_000),
            FleetGoal(ShipType.SHIP_MINING_DRONE, 2, reserve = 150_000),
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

    /** The job a ship gets when nobody said otherwise: a bought ship, or a fresh agent's plan. [forSystem] is the system it was bought for. */
    fun defaultAssignment(phase: Phase, ship: Ship, snapshot: Snapshot, forSystem: String? = null): Assignment? {
        if (phase == Phase.BOOM && forSystem != null) {
            // Born for a system in the boom: probes chart it, then watch it; haulers trade it. Both migrate if bought elsewhere.
            val there = ship.nav.systemSymbol == forSystem
            return when {
                !ship.usesFuel -> Assignment(ship.symbol, "chartSystem", if (there) emptyMap() else mapOf("system" to forSystem))
                ship.cargo.capacity >= 40 && !ship.canMine -> Assignment(ship.symbol, "trade", if (there) emptyMap() else mapOf("system" to forSystem))
                else -> Behaviours.defaultFor(ship)?.let { Assignment(ship.symbol, it) }
            }
        }
        val home = snapshot.hqSystem
        val site = home?.let { h -> snapshot.waypointsIn(h).firstOrNull { it.isUnderConstruction } }
        val isHauler = ship.usesFuel && ship.cargo.capacity >= 60 && !ship.canMine
        return when (phase) {
            Phase.ESCAPE -> when {
                // Haulers by order of arrival: the first supplies the gate (nursing its producers), the second keeps the
                // contract drip going, the third trades and gardens, and every further one goes to the gate.
                isHauler && site != null && ship.nav.systemSymbol == home -> {
                    val haulersBefore = snapshot.ships.values.count { it.symbol != ship.symbol && it.symbol < ship.symbol && it.usesFuel && it.cargo.capacity >= 60 && !it.canMine }
                    val parkedDrones = snapshot.plan?.assignments?.any { it.behaviour == "mineInPlace" } == true
                    val collectorExists = snapshot.plan?.assignments?.any { it.behaviour == "collect" } == true
                    when {
                        haulersBefore == 0 -> Assignment(ship.symbol, "supplyGate", mapOf("site" to site.symbol, "reserve" to "200000"))
                        haulersBefore == 1 -> Assignment(ship.symbol, "runContract")
                        // Drones parked on rocks need a collector before another trader.
                        parkedDrones && !collectorExists -> Assignment(ship.symbol, "collect")
                        snapshot.plan?.rushing == true -> Assignment(ship.symbol, "supplyGate", mapOf("site" to site.symbol, "reserve" to "200000"))
                        // After the crew, FEEDERS haulers work the gate chains, each joining the chain with the fewest hands.
                        haulersBefore >= ESCAPE_CREW && (snapshot.plan?.assignments?.count { it.behaviour == "feed" && it.ship != ship.symbol } ?: 0) < FEEDERS &&
                            snapshot.plan?.chains?.any { it.id.startsWith(GATE_CHAIN) } == true ->
                            Assignment(ship.symbol, "feed", mapOf("chain" to snapshot.plan.chains.filter { it.id.startsWith(GATE_CHAIN) }.minBy { it.ships.size }.id))
                        else -> Assignment(ship.symbol, "trade")
                    }
                }
                // A drone where no rock is within its tank of a buyer sits on a rock and waits for the collector.
                ship.canMine && ship.cargo.capacity in 1..20 && !dronesWorthIt(snapshot) -> Assignment(ship.symbol, "mineInPlace")
                // At a reset the home system is uncharted and each chart paid ~28k on 2026-09-05: the probe charts
                // before it reads prices. That is the fastest money there is in the first hours, and it funds the gate.
                !ship.usesFuel && home != null && snapshot.waypointsIn(home).any { it.hasTrait(model.WaypointTraitSymbol.UNCHARTED) } &&
                    snapshot.plan?.assignments?.none { it.behaviour == "chartSystem" && it.ship != ship.symbol } != false ->
                    Assignment(ship.symbol, "chartSystem")
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
                // A probe bought without a system in mind pioneers while the frontier has room for one, else charts where it is.
                !ship.usesFuel && (snapshot.plan?.assignments?.count { it.behaviour == "pioneer" } ?: 0) < PIONEERS -> Assignment(ship.symbol, "pioneer")
                !ship.usesFuel -> Assignment(ship.symbol, "chartSystem")
                else -> Behaviours.defaultFor(ship)?.let { Assignment(ship.symbol, it) }
            }
            Phase.LATE -> Behaviours.defaultFor(ship)?.let { Assignment(ship.symbol, it) }
        }
    }

    /**
     * The plan for the ships that already exist when the phase changes. BOOM: home becomes a settled
     * system, the home gate's connections become the frontier, the first probe keeps reading home's
     * prices and the next [PIONEERS] pioneer; haulers keep trading at home (gardening when routes
     * run out) until a system's rush kit or network needs them. [neighbours] are the far gates.
     */
    fun rebalance(phase: Phase, plan: Plan, snapshot: Snapshot, neighbours: List<String>): Plan {
        if (phase != Phase.BOOM) return plan
        val home = snapshot.hqSystem
        var next = plan
        if (home != null) {
            val gate = snapshot.waypointsIn(home).firstOrNull { it.type == model.system.WaypointType.JUMP_GATE }
            next = next.withSystem(SystemRecord(home, Stage.SETTLE, gate = gate?.symbol, gateBuilt = true, arrivedAt = snapshot.creditsHistory.firstOrNull()?.at?.toString(), note = "home"))
            next = next.withFrontier(neighbours.map { FrontierGate(it, home) })
        }
        val ships = snapshot.ships.values.sortedBy { it.symbol }
        val probes = ships.filter { !it.usesFuel }
        probes.forEachIndexed { i, probe ->
            next = next.with(
                when {
                    i == 0 -> Assignment(probe.symbol, "probeMarkets", mapOf("maxAge" to "10"))
                    i <= PIONEERS -> Assignment(probe.symbol, "pioneer")
                    else -> Assignment(probe.symbol, "chartSystem")
                },
            )
        }
        // The frigate carries a laser but is a trader in the boom: anything with a real hold trades.
        ships.filter { it.usesFuel && it.cargo.capacity >= 40 }.forEach { hauler -> next = next.with(Assignment(hauler.symbol, "trade")) }
        return next
    }

    /**
     * The boom's stage transitions, from facts: a rushed system whose waypoints are all charted and
     * markets all read moves to NETWORK when its gate is unbuilt (one hauler there goes to the gate
     * with the network's share of the bank) and to SETTLE otherwise; a networked system settles
     * when its gate completes. Pure: the same plan comes back when nothing changes.
     */
    fun advanceSystems(plan: Plan, snapshot: Snapshot, now: java.time.Instant): Plan {
        var next = plan
        for (record in plan.systems.values) {
            val waypoints = snapshot.waypointsIn(record.symbol)
            if (waypoints.isEmpty()) continue
            val charted = waypoints.none { it.hasTrait(model.WaypointTraitSymbol.UNCHARTED) }
            val markets = waypoints.count { it.hasMarket }
            val read = snapshot.pricedMarketsIn(record.symbol).size
            val gate = record.gate?.let { snapshot.waypoints[it] } ?: waypoints.firstOrNull { it.type == model.system.WaypointType.JUMP_GATE }
            val gateUnbuilt = gate?.isUnderConstruction == true
            when (record.stage) {
                // "Read" allows for the odd market that shows no prices even with a ship present.
                Stage.RUSH -> if (charted && read >= (markets * 0.9).toInt()) {
                    next = next.withSystem(record.copy(stage = if (gateUnbuilt) Stage.NETWORK else Stage.SETTLE, gateBuilt = !gateUnbuilt))
                }
                Stage.NETWORK -> {
                    if (!gateUnbuilt) next = next.withSystem(record.copy(stage = Stage.SETTLE, gateBuilt = true))
                    else if (gate != null && next.assignments.none { it.behaviour == "supplyGate" && it.params["site"] == gate.symbol }) {
                        // One hauler in the system goes to its gate, drawing at most the network's share of the bank.
                        val hauler = snapshot.ships.values.filter { it.nav.systemSymbol == record.symbol && it.usesFuel && it.cargo.capacity >= 40 }
                            .sortedByDescending { it.cargo.capacity }
                            .firstOrNull { s -> next.assignmentFor(s.symbol)?.behaviour == "trade" }
                        if (hauler != null) next = next.with(Assignment(hauler.symbol, "supplyGate", mapOf("site" to gate.symbol, "reserveShare" to (1 - NETWORK_SHARE).toString())))
                    }
                }
                Stage.SETTLE -> {
                    // Extra probes beyond the watcher pioneer while the frontier has room.
                    val probesHere = snapshot.ships.values.filter { it.nav.systemSymbol == record.symbol && !it.usesFuel }.sortedBy { it.symbol }
                    val pioneers = next.assignments.count { it.behaviour == "pioneer" }
                    probesHere.drop(SETTLE_PROBES).filter { next.assignmentFor(it.symbol)?.behaviour in setOf("probeMarkets", "chartSystem", null) }
                        .take((PIONEERS - pioneers).coerceAtLeast(0))
                        .forEach { next = next.with(Assignment(it.symbol, "pioneer")) }
                }
                Stage.CASCADE -> {}
            }
        }
        return next
    }

    /** The plan a just-registered agent starts with: every ship's default job for [phase], the phase's goals. */
    fun freshPlan(phase: Phase, snapshot: Snapshot): Plan = Plan(
        assignments = snapshot.ships.values.sortedBy { it.symbol }.mapNotNull { defaultAssignment(phase, it, snapshot) },
        goals = goals(phase, snapshot),
        phase = phase,
    )

    /** What a ship does once its behaviour has run to completion; null leaves it finished. */
    fun afterFinished(phase: Phase, ship: Ship, behaviour: String, snapshot: Snapshot): Assignment? = when {
        // The gate is done: its haulers become the boom's traders.
        behaviour == "supplyGate" && ship.cargo.capacity > 0 -> Assignment(ship.symbol, "trade")
        // Home charted: now read every market once; in the boom a charted system just needs its prices watched.
        !ship.usesFuel && behaviour == "chartSystem" && phase == Phase.BOOM -> Assignment(ship.symbol, "probeMarkets", mapOf("maxAge" to "10"))
        !ship.usesFuel && behaviour == "chartSystem" -> Assignment(ship.symbol, "probeMarkets")
        // The probe has read every market: park it at a yard and buy the fleet the goals ask for, if any is still unmet.
        !ship.usesFuel && behaviour == "probeMarkets" && goalsUnmet(snapshot) -> Assignment(ship.symbol, "expand")
        // Otherwise, and when an explorer runs out of map, keep the prices fresh where it stands.
        !ship.usesFuel && (behaviour == "probeMarkets" || behaviour == "explore") -> Assignment(ship.symbol, "probeMarkets", mapOf("maxAge" to "10"))
        else -> null
    }

    /** Whether any fleet goal still wants a ship. */
    fun goalsUnmet(snapshot: Snapshot): Boolean = snapshot.plan?.goals?.fleet?.any { goal -> goal.owned(snapshot.ships.values) < goal.count } == true

    fun describe(phase: Phase): String = when (phase) {
        Phase.ESCAPE -> "ESCAPE: market health first; profits fund the logistics that keep producers fed and the gate supplied"
        Phase.BOOM -> "BOOM: the gate is open; probes explore and chart, traders drain fresh systems"
        Phase.LATE -> "LATE: profit first with soft health weights; measuring where markets tip"
    }

    /** The type a ship counts as for fleet goals, for callers without a scope. */
    fun typeOf(ship: Ship): ShipType? = BehaviourScope.shipTypeOf(ship)
}

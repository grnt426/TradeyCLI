package behaviour.decisions

import behaviour.Behaviours
import engine.Snapshot
import knowledge.MarketAssumptions
import knowledge.MarketHealth
import knowledge.Strategy
import model.market.TradeGoodType
import model.market.TradeSymbol
import model.market.TransactionType
import model.ship.ShipNavStatus
import plan.Phase
import java.time.Duration
import java.time.Instant

/** Credits attributed to one purpose or source. */
data class Flow(val category: String, val credits: Long, val share: Double)

/** One system's market health from the latest readings. */
data class SystemHealth(val system: String, val markets: Int, val listings: Int, val score: Double, val restrictedExports: Int, val scarce: Int, val saturatedImports: Int, val oldestReadHours: Double?)

/** One ship on the summary screen. */
data class FleetRow(val ship: String, val type: String, val behaviour: String, val phase: String, val detail: String, val where: String, val cargo: String, val tone: Intent.Tone)

/** Where the phase stands, in a few lines. */
data class Progress(val headline: String, val lines: List<Intent>)

/**
 * The summary screen's numbers, from the ledger the sinks keep: every market transaction with
 * the tag the ship carried (its behaviour, a chain id, `gate:SITE`, `nurse:SITE`) and every credit
 * move outside a market (ships, charts, contracts). Pure: a snapshot in, tables out. Fuel is
 * charged to the purpose whose ship burned it, so each category is its full operating cost.
 */
object Summary {

    const val GATE = "gate"
    const val HEALTH = "market health"
    const val TRADING = "trading"
    const val MINING = "mining"
    const val CONTRACTS = "contracts"
    const val EXPLORING = "exploring"
    const val SHIPS = "ships (capex)"
    const val CHARTING = "charting"
    const val OTHER = "other"

    private val exploring = setOf("explore", "chartSystem", "probeMarkets", "expand")

    /** The spending purpose of a transaction from its tag. */
    fun purposeOf(tag: String?): String = when {
        tag == null -> OTHER
        tag.startsWith("gate:") -> GATE
        tag.startsWith("nurse:") -> HEALTH
        tag == "trade" -> TRADING
        tag == "mineAndSell" -> MINING
        tag == "runContract" -> CONTRACTS
        tag in exploring -> EXPLORING
        tag in Behaviours.all -> OTHER
        else -> HEALTH // a chain id: worker bees feeding a producer
    }

    /** The revenue source of a sale from its tag. */
    fun sourceOf(tag: String?): String = when {
        tag == null -> OTHER
        tag == "trade" -> "arbitrage"
        tag == "mineAndSell" -> MINING
        tag == "runContract" -> CONTRACTS
        tag.startsWith("gate:") || tag.startsWith("nurse:") -> HEALTH
        tag in exploring -> EXPLORING
        tag in Behaviours.all -> OTHER
        else -> HEALTH
    }

    /** The window the screen's money panels cover: transactions before the ledger existed carry no purpose. */
    val WINDOW: Duration = Duration.ofHours(24)

    /** Credits out by purpose since [since], largest first. Purchases and fuel go to the purpose; ship purchases are capex. */
    fun spending(snapshot: Snapshot, since: Instant? = null): List<Flow> {
        val out = mutableMapOf<String, Long>()
        snapshot.taggedTransactions.filter { it.transaction.type == TransactionType.PURCHASE && (since == null || Instant.parse(it.transaction.timestamp) >= since) }
            .forEach { out.merge(purposeOf(it.tag), it.transaction.totalPrice.toLong(), Long::plus) }
        snapshot.ledger.filter { it.credits < 0 && (since == null || it.at >= since) }
            .forEach { out.merge(if (it.kind == "ships") SHIPS else OTHER, -it.credits, Long::plus) }
        return flows(out)
    }

    /** Credits in by source since [since], largest first. Sales go to the behaviour that sold; charts and contracts come from the ledger. */
    fun revenue(snapshot: Snapshot, since: Instant? = null): List<Flow> {
        val out = mutableMapOf<String, Long>()
        snapshot.taggedTransactions.filter { it.transaction.type == TransactionType.SELL && (since == null || Instant.parse(it.transaction.timestamp) >= since) }
            .forEach { out.merge(sourceOf(it.tag), it.transaction.totalPrice.toLong(), Long::plus) }
        snapshot.ledger.filter { it.credits > 0 && (since == null || it.at >= since) }
            .forEach { out.merge(when (it.kind) { "chart" -> CHARTING; "contract" -> CONTRACTS; else -> OTHER }, it.credits, Long::plus) }
        return flows(out)
    }

    private fun flows(map: Map<String, Long>): List<Flow> {
        val total = map.values.sum().coerceAtLeast(1)
        return map.entries.sortedByDescending { it.value }.map { (k, v) -> Flow(k, v, v.toDouble() / total) }
    }

    /** Average listing health per system from the latest readings, with the counts that explain it. */
    fun marketHealth(snapshot: Snapshot, now: Instant, rules: MarketAssumptions = MarketAssumptions()): List<SystemHealth> =
        snapshot.markets.values.filter { it.hasPrices }.groupBy { it.symbol.substringBeforeLast('-') }.map { (system, markets) ->
            val listings = markets.flatMap { it.tradeGoods }
            val oldest = markets.mapNotNull { it.lastRead }.minOrNull()?.let { Duration.between(it, now).toMinutes() / 60.0 }
            SystemHealth(
                system = system,
                markets = markets.size,
                listings = listings.size,
                score = if (listings.isEmpty()) 0.0 else listings.map { MarketHealth.score(it, rules) }.average(),
                restrictedExports = listings.count { it.type == TradeGoodType.EXPORT && it.activity == model.market.ActivityLevel.RESTRICTED },
                scarce = listings.count { it.supply == model.market.SupplyLevel.SCARCE },
                saturatedImports = listings.count { it.type == TradeGoodType.IMPORT && it.supply == model.market.SupplyLevel.ABUNDANT },
                oldestReadHours = oldest,
            )
        }.sortedBy { it.system }

    /** The fleet, one row per ship, the plan's ships first. */
    fun fleet(snapshot: Snapshot, now: Instant): List<FleetRow> = snapshot.ships.values.sortedBy { it.symbol }.map { ship ->
        val status = snapshot.shipStatus[ship.symbol]
        val assignment = snapshot.plan?.assignmentFor(ship.symbol)
        val where = when (ship.nav.status) {
            ShipNavStatus.IN_TRANSIT -> "-> ${ship.nav.route.destination.symbol} ${Duration.between(now, ship.nav.route.arrival).toMinutes().coerceAtLeast(0)}m"
            else -> ship.nav.waypointSymbol
        }
        val cargo = if (ship.cargo.capacity == 0) "" else "${ship.cargo.units}/${ship.cargo.capacity}" + (ship.cargo.inventory.firstOrNull()?.let { " ${it.symbol.name.take(12)}" } ?: "")
        val tone = when {
            status?.phase == "failed" -> Intent.Tone.WARN
            assignment != null && status == null -> Intent.Tone.WARN
            status?.phase == "waiting" || status?.phase == "idle" || status?.phase == "done" -> Intent.Tone.NEUTRAL
            else -> Intent.Tone.GOOD
        }
        FleetRow(
            ship = ship.symbol.substringAfterLast('-'),
            type = Strategy.typeOf(ship)?.name?.removePrefix("SHIP_") ?: ship.frame.symbol.removePrefix("FRAME_"),
            behaviour = status?.behaviour ?: assignment?.behaviour ?: "idle",
            phase = status?.phase ?: "",
            detail = status?.detail ?: "",
            where = where,
            cargo = cargo,
            tone = tone,
        )
    }

    /** Where the phase stands: the gate in ESCAPE, the map and the bank in BOOM. */
    fun progress(snapshot: Snapshot, now: Instant, trend: Trend): Progress {
        val phase = snapshot.plan?.phase ?: Phase.ESCAPE
        val credits = snapshot.agent?.credits ?: 0L
        val lines = mutableListOf<Intent>()
        return when (phase) {
            Phase.ESCAPE -> {
                val site = snapshot.hqSystem?.let { h -> snapshot.waypointsIn(h).firstOrNull { it.isUnderConstruction } }
                if (site == null) return Progress("ESCAPE: no construction site in ${snapshot.hqSystem}; `phase boom` when the gate is open", lines)
                val delivered = snapshot.taggedTransactions.filter { it.tag == "gate:${site.symbol}" && it.transaction.type == TransactionType.PURCHASE && it.transaction.tradeSymbol != TradeSymbol.FUEL }
                val spent = spending(snapshot).filter { it.category == GATE || it.category == HEALTH }.sumOf { it.credits }
                val bill = snapshot.constructionBill
                val remainingCost = bill?.let { Strategy.remainingCost(it, snapshot) }
                val delivering = delivered.filter { Instant.parse(it.transaction.timestamp) >= now.minus(Duration.ofHours(2)) }.sumOf { it.transaction.units }
                val ratePerHour = delivering / 2.0
                val remainingUnits = bill?.sumOf { it.required - it.fulfilled } ?: 0L
                val headline = if (bill == null) "ESCAPE: gate ${site.symbol}; read the site to see the bill"
                else "ESCAPE: gate ${site.symbol} " + bill.joinToString(", ") { "${it.tradeSymbol.name} ${it.fulfilled}/${it.required}" }
                if (bill != null) {
                    lines += Intent("Spent ${Intentions.format(spent)} on the gate and on feeding its producers so far", Intent.Tone.NEUTRAL)
                    if (remainingCost != null) {
                        val haulerGoal = snapshot.plan?.goals?.fleet?.firstOrNull { it.type == model.ship.ShipType.SHIP_LIGHT_HAULER }?.count ?: 0
                        val rushing = snapshot.plan?.rushing == true
                        val comfortable = rushing || Strategy.gateRush(credits, remainingCost)
                        lines += Intent(
                            "To finish: about ${Intentions.format(remainingCost)} at today's prices against a bank of ${Intentions.format(credits)}" +
                                when {
                                    rushing -> "; rushing with $haulerGoal haulers, drawing at the producers' healthy rate"
                                    comfortable -> "; comfortable, the rush starts on the hauler's next check"
                                    else -> "; not yet comfortable (need ${Intentions.format(Strategy.comfortableBank(remainingCost))})"
                                },
                            if (comfortable) Intent.Tone.GOOD else Intent.Tone.NEUTRAL,
                        )
                    }
                    lines += if (ratePerHour > 0) Intent("Delivering ${ratePerHour.toInt()} units/h; about ${"%.1f".format(remainingUnits / ratePerHour)} h of hauling left", Intent.Tone.GOOD)
                    else Intent("No deliveries in the last two hours", Intent.Tone.WARN)
                    chainHealth(snapshot)?.let { h -> lines += Intent("Gate chains' health ${(h * 100).toInt()}% (`chains` for every input down to the ores)", if (h >= 0.6) Intent.Tone.GOOD else Intent.Tone.NEUTRAL) }
                }
                Progress(headline, lines)
            }
            Phase.BOOM -> {
                val systems = snapshot.systems.size
                val charted = snapshot.ledger.count { it.kind == "chart" }
                val chartIncome = snapshot.ledger.filter { it.kind == "chart" }.sumOf { it.credits }
                val readMarkets = snapshot.markets.values.count { it.hasPrices }
                lines += Intent("${systems} systems loaded, $readMarkets markets with prices, $charted waypoints charted for ${Intentions.format(chartIncome)}", Intent.Tone.NEUTRAL)
                lines += Intent("Bank ${Intentions.format(credits)}, ${signed(trend.perHour.toLong())}/h over the last half hour", if (trend.perHour >= 0) Intent.Tone.GOOD else Intent.Tone.WARN)
                Progress("BOOM: explore, chart and drain fresh systems", lines)
            }
            Phase.LATE -> Progress("LATE: profit first; the summary screen is designed for ESCAPE and BOOM so far", lines)
        }
    }

    /** The gate chains' average listing health in the home system, 0..1, or null without a bill. */
    fun chainHealth(snapshot: Snapshot, rules: MarketAssumptions = MarketAssumptions()): Double? {
        val home = snapshot.hqSystem ?: return null
        val roots = snapshot.constructionBill?.filter { it.fulfilled < it.required }?.map { it.tradeSymbol } ?: return null
        val goods = linkedSetOf<TradeSymbol>()
        fun walk(g: TradeSymbol, depth: Int) { if (goods.add(g) && depth < 4) knowledge.ImportMap.inputs[g].orEmpty().forEach { walk(it, depth + 1) } }
        roots.forEach { walk(it, 0) }
        val scores = snapshot.marketsIn(home).flatMap { m -> goods.mapNotNull { m.good(it) } }.map { MarketHealth.score(it, rules) }
        return scores.takeIf { it.isNotEmpty() }?.average()
    }

    /** One line on the fleet's idle share, or null before there is any phase history. */
    fun idleLine(snapshot: Snapshot, now: Instant): String? = idleLine(snapshot, Idle.perShip(snapshot.phases, now))

    /** [idleLine] from figures already worked out. */
    fun idleLine(snapshot: Snapshot, ships: List<ShipIdle>): String? {
        if (ships.isEmpty()) return null
        val worst = ships.firstOrNull { it.share > 0.3 }
        val drifts = snapshot.activities.filter { it.kind == "drift" }
        val driftSeconds = drifts.sumOf { it.seconds }
        return "fleet idle ${(Idle.fleetShare(ships) * 100).toInt()}% of the last day" + (worst?.let { "; worst ${it.ship.substringAfterLast('-')} at ${(it.share * 100).toInt()}%" } ?: "") +
            (if (driftSeconds > 0) "; drifted ${"%.1f".format(driftSeconds / 3600.0)} h on ${drifts.size} legs" else "")
    }

    private fun signed(n: Long): String = (if (n >= 0) "+" else "") + Intentions.format(n)
}

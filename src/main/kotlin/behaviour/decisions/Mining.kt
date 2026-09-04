package behaviour.decisions

import engine.Snapshot
import engine.Travel
import knowledge.DefaultPrices
import knowledge.Deposits
import model.market.Market
import model.market.TradeSymbol
import model.ship.FlightMode
import model.ship.Ship
import model.system.Waypoint
import model.system.WaypointModifiers
import model.WaypointTraitSymbol
import java.time.Instant
import kotlin.math.ceil

/** One way to make money: this asteroid, that market, and what an hour of it should pay. */
data class MiningPlan(
    val asteroid: Waypoint,
    val market: Market,
    /** Credits per hour after fuel, times [risk]. */
    val creditsPerHour: Double,
    /** Credits per unit put into the hold, counting only goods the market buys. */
    val valuePerUnit: Double,
    /** Share of extractions the market will buy; the rest gets dropped. */
    val tradedShare: Double,
    val cycleSeconds: Long,
    val distance: Double,
    val fuelPerCycle: Long,
    /** How the ship gets back to the market: straight on cruise, through a fuel stop, or drifting. */
    val returnLeg: ReturnLeg,
    /** 1.0 for a healthy rock; lower for unstable, fragile, hazardous or crowded ones. */
    val risk: Double,
    val riskNotes: List<String>,
    /** Prices used, per good the market buys. */
    val prices: Map<TradeSymbol, Int>,
    /** True when any price is a guess rather than an observation. */
    val estimated: Boolean,
) {
    fun summary(): String =
        "${asteroid.symbol} -> ${market.symbol}: ~${creditsPerHour.toInt()} cr/h, ${"%.0f".format(valuePerUnit)} cr/unit, " +
            "${(tradedShare * 100).toInt()}% sellable, cycle ${cycleSeconds / 60}m, ${distance.toInt()} away" +
            returnLeg.note().let { if (it.isEmpty()) "" else ", $it" } +
            (if (estimated) " (prices estimated)" else "") + (if (risk < 1.0) " risk ${"%.2f".format(risk)} [${riskNotes.joinToString(", ")}]" else "")
}

/** The way back from the rock with what is left in the tank. */
sealed class ReturnLeg {
    /** The tank covers the round trip. */
    object Cruise : ReturnLeg()

    /** Refuel at [via] on the way back; both hops on cruise. */
    data class Via(val via: Waypoint, val toVia: Double, val viaToMarket: Double) : ReturnLeg()

    /** Nothing reachable sells fuel: drift back on one unit, ten times slower. */
    object Drift : ReturnLeg()

    fun note(): String = when (this) {
        Cruise -> ""
        is Via -> "refuel at ${via.symbol} on the way back"
        Drift -> "drifts back (round trip exceeds tank)"
    }
}

/** What the extraction log says about one rock (or gas giant). */
data class ObservedRock(
    val waypoint: String,
    val extractions: Int,
    val unitsPerExtraction: Double,
    /** Extractions per hour over the observed span, when the span is long enough to say. */
    val perHour: Double?,
    /** Modifiers the rock has reported, worst last. */
    val modifiersSeen: List<String>,
    val lastAt: Instant,
) {
    fun summary(): String = "$extractions ext, ${"%.1f".format(unitsPerExtraction)}/ext" +
        (perHour?.let { ", ${"%.0f".format(it)}/h" } ?: "") + (if (modifiersSeen.isEmpty()) "" else ", seen ${modifiersSeen.joinToString("+")}")
}

/** What the ranking assumes about the game; the simulator's rules are the same numbers. */
data class MiningAssumptions(
    val extractCooldownSeconds: Long = 70,
    /** Units per extraction per point of laser strength (midpoint of the simulator's range). */
    val yieldPerStrength: Double = 2.0,
    /** Credits per ship fuel unit: 72 per market unit of 100. */
    val creditsPerFuelUnit: Double = 0.72,
    /** Seconds for docking, selling and refueling per cycle. */
    val overheadSeconds: Long = 20,
    val crowdedRadius: Double = 60.0,
)

object Mining {

    /** Per-waypoint yield history from the extraction log: what a rock actually gives, and how hard it has been worked. */
    fun observe(records: List<storage.ExtractionRecord>, now: Instant): Map<String, ObservedRock> =
        records.groupBy { it.waypoint }.mapValues { (waypoint, list) ->
            val sorted = list.sortedBy { it.at }
            val spanHours = (sorted.last().at.toEpochMilli() - sorted.first().at.toEpochMilli()) / 3_600_000.0
            ObservedRock(
                waypoint = waypoint,
                extractions = sorted.size,
                unitsPerExtraction = sorted.map { it.units }.average(),
                perHour = if (spanHours >= 0.25) (sorted.size - 1) / spanHours else null,
                modifiersSeen = sorted.flatMap { it.modifiers }.distinct(),
                lastAt = sorted.last().at,
            )
        }

    /**
     * Every asteroid (and, for a ship with a siphon, gas giant) paired with every market that buys
     * something it yields, best first. Yield per extraction is what the log has seen at that rock
     * once three or more extractions are recorded, else the laser-strength guess. Prices come from
     * markets a ship has visited; the rest are guesses and flagged as such.
     */
    fun rank(snapshot: Snapshot, ship: Ship, now: Instant, assumptions: MiningAssumptions = MiningAssumptions()): List<MiningPlan> {
        val system = ship.nav.systemSymbol
        val hq = snapshot.agent?.headquarters?.let { snapshot.waypoints[it] }
        val markets = snapshot.marketsIn(system)
        val capacity = ship.cargo.capacity.takeIf { it > 0 } ?: return emptyList()
        val observed = observe(snapshot.extractions, now)
        val candidates = (if (ship.canMine) snapshot.asteroidsIn(system) else emptyList()) + (if (ship.canSiphon) snapshot.gasGiantsIn(system) else emptyList())

        return candidates.flatMap { asteroid ->
            val mix = if (asteroid.isSiphonable) Deposits.gasGiant else Deposits.yieldMix(asteroid.traitSymbols)
            if (mix.isEmpty()) return@flatMap emptyList()
            if (asteroid.hasTrait(WaypointTraitSymbol.STRIPPED) || asteroid.hasModifier(WaypointModifiers.STRIPPED)) return@flatMap emptyList()
            val (risk, notes) = riskOf(asteroid, hq, assumptions)
            val strength = if (asteroid.isSiphonable) ship.siphonStrength else ship.miningStrength
            val seen = observed[asteroid.symbol]?.takeIf { it.extractions >= 3 }
            val yieldPerExtract = seen?.unitsPerExtraction ?: (strength * assumptions.yieldPerStrength).coerceAtLeast(1.0)
            val observedNotes = seen?.let { listOf("observed ${it.summary()}") } ?: emptyList()
            markets.mapNotNull { market ->
                val marketWaypoint = snapshot.waypoints[market.symbol] ?: return@mapNotNull null
                val prices = mutableMapOf<TradeSymbol, Int>()
                var estimated = false
                mix.keys.filter { market.trades(it) }.forEach { good ->
                    val seen = market.sellPriceOf(good)
                    if (seen != null) prices[good] = seen else {
                        prices[good] = DefaultPrices.sell(good, market.typeOf(good)!!)
                        estimated = true
                    }
                }
                if (prices.isEmpty()) return@mapNotNull null
                val tradedShare = prices.keys.sumOf { mix.getValue(it) }
                val valuePerExtractedUnit = prices.entries.sumOf { (good, price) -> mix.getValue(good) * price }
                val valuePerUnit = valuePerExtractedUnit / tradedShare
                val distance = Travel.distance(asteroid.x, asteroid.y, marketWaypoint.x, marketWaypoint.y)
                val extracts = ceil(capacity / (yieldPerExtract * tradedShare)).toLong()
                val fillSeconds = extracts * assumptions.extractCooldownSeconds
                val outbound = Travel.fuelCost(distance, FlightMode.CRUISE)
                if (ship.usesFuel && outbound > ship.fuel.capacity) return@mapNotNull null
                val returnLeg = returnLeg(ship, asteroid, marketWaypoint, distance, snapshot)
                val speed = ship.engine.speed
                val travelSeconds = Travel.seconds(distance, FlightMode.CRUISE, speed) + when (returnLeg) {
                    ReturnLeg.Cruise -> Travel.seconds(distance, FlightMode.CRUISE, speed)
                    is ReturnLeg.Via -> Travel.seconds(returnLeg.toVia, FlightMode.CRUISE, speed) + Travel.seconds(returnLeg.viaToMarket, FlightMode.CRUISE, speed) + assumptions.overheadSeconds
                    ReturnLeg.Drift -> Travel.seconds(distance, FlightMode.DRIFT, speed)
                }
                val cycleSeconds = fillSeconds + travelSeconds + assumptions.overheadSeconds
                val fuelPerCycle = if (!ship.usesFuel) 0L else outbound + when (returnLeg) {
                    ReturnLeg.Cruise -> outbound
                    is ReturnLeg.Via -> Travel.fuelCost(returnLeg.toVia, FlightMode.CRUISE) + Travel.fuelCost(returnLeg.viaToMarket, FlightMode.CRUISE)
                    ReturnLeg.Drift -> 1L
                }
                val creditsPerCycle = capacity * valuePerUnit - fuelPerCycle * assumptions.creditsPerFuelUnit
                val perHour = creditsPerCycle / cycleSeconds * 3600 * risk
                MiningPlan(asteroid, market, perHour, valuePerUnit, tradedShare, cycleSeconds, distance, fuelPerCycle, returnLeg, risk, notes + observedNotes, prices, estimated)
            }
        }.sortedByDescending { it.creditsPerHour }
    }

    /**
     * How to get from the rock back to the market with what a full tank leaves after the trip out.
     * Prefers cruising straight back, then the fuel stop that adds the least distance, then drifting.
     */
    fun returnLeg(ship: Ship, asteroid: Waypoint, market: Waypoint, distance: Double, snapshot: Snapshot): ReturnLeg {
        if (!ship.usesFuel) return ReturnLeg.Cruise
        val left = ship.fuel.capacity - Travel.fuelCost(distance, FlightMode.CRUISE)
        if (Travel.fuelCost(distance, FlightMode.CRUISE) <= left) return ReturnLeg.Cruise
        val stop = snapshot.waypointsIn(asteroid.systemSymbol)
            .filter { it.hasMarket && it.symbol != market.symbol && snapshot.markets[it.symbol]?.trades(TradeSymbol.FUEL) != false }
            .map { via -> ReturnLeg.Via(via, Travel.distance(asteroid.x, asteroid.y, via.x, via.y), Travel.distance(via.x, via.y, market.x, market.y)) }
            .filter { Travel.fuelCost(it.toVia, FlightMode.CRUISE) <= left && Travel.fuelCost(it.viaToMarket, FlightMode.CRUISE) <= ship.fuel.capacity }
            .minByOrNull { it.toVia + it.viaToMarket }
        return stop ?: ReturnLeg.Drift
    }

    /** Best plan per asteroid, for a readable table. */
    fun bestPerAsteroid(plans: List<MiningPlan>): List<MiningPlan> =
        plans.groupBy { it.asteroid.symbol }.values.map { it.first() }.sortedByDescending { it.creditsPerHour }

    /**
     * How likely the rock is to keep paying. Modifiers are the game telling us it is wearing out;
     * fragile traits hint it will not take much; hazard traits cost ship condition; rocks next to
     * headquarters get stripped by everyone else.
     */
    fun riskOf(asteroid: Waypoint, hq: Waypoint?, assumptions: MiningAssumptions = MiningAssumptions()): Pair<Double, List<String>> {
        var risk = 1.0
        val notes = mutableListOf<String>()
        if (asteroid.hasModifier(WaypointModifiers.CRITICAL_LIMIT)) { risk *= 0.25; notes += "CRITICAL_LIMIT" }
        else if (asteroid.hasModifier(WaypointModifiers.UNSTABLE)) { risk *= 0.7; notes += "UNSTABLE" }
        if (asteroid.hasModifier(WaypointModifiers.RADIATION_LEAK)) { risk *= 0.8; notes += "RADIATION_LEAK" }
        val fragile = asteroid.traitSymbols.intersect(Deposits.fragileTraits)
        if (fragile.isNotEmpty()) { risk *= 0.85; notes += fragile.joinToString("+") { it.name.lowercase() } }
        val hazards = asteroid.traitSymbols.intersect(Deposits.hazardTraits)
        if (hazards.isNotEmpty()) { risk *= (1 - 0.04 * hazards.size).coerceAtLeast(0.85); notes += "${hazards.size} hazard trait(s)" }
        if (hq != null && Travel.distance(hq.x, hq.y, asteroid.x, asteroid.y) <= assumptions.crowdedRadius) { risk *= 0.8; notes += "next to HQ, crowded" }
        return risk to notes
    }
}

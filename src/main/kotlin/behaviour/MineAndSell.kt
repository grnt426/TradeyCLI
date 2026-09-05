package behaviour

import behaviour.decisions.MiningPlan
import behaviour.decisions.Mining
import behaviour.decisions.ReturnLeg
import behaviour.decisions.Selling
import behaviour.decisions.Surveys
import engine.Travel
import engine.VerbFailure
import model.ship.FlightMode
import model.system.WaypointModifiers
import kotlin.time.Duration.Companion.seconds

/**
 * Fill up at an asteroid, sell at a market, repeat. With no asteroid or market given it picks the
 * pair the ranking likes best each cycle, so it follows prices and avoids rocks that are wearing
 * out. Goods the market will not buy are dropped at the asteroid to keep the hold for what sells.
 */
val mineAndSellSpec = BehaviourSpec(
    name = "mineAndSell",
    description = "Mine an asteroid (or siphon a gas giant) until full, sell at a market, refuel, repeat. Picks the best pair itself unless told; one ship per rock.",
    params = listOf(
        ParamSpec("asteroid", "Asteroid to mine; default: best by ranking, re-chosen every cycle"),
        ParamSpec("market", "Market to sell at; default: best for the asteroid"),
        ParamSpec("surveys", "yes/no: use the surveyor mount when the ship has one (default yes)"),
    ),
    validate = { snapshot, ship, params ->
        buildList {
            if (!ship.canMine && !ship.canSiphon) add("${ship.symbol} has no mining laser or gas siphon")
            params["asteroid"]?.let { a ->
                val w = snapshot.waypoints[a]
                if (w == null) add("unknown waypoint $a") else if (!w.isMineable && !w.isSiphonable) add("$a is a ${w.type}, not an asteroid or gas giant")
                else if (w.systemSymbol != ship.nav.systemSymbol) add("$a is not in ${ship.nav.systemSymbol}")
            }
            params["market"]?.let { m ->
                val w = snapshot.waypoints[m]
                if (w == null) add("unknown waypoint $m") else if (!w.hasMarket) add("$m has no market")
            }
        }
    },
    run = { mineAndSell() },
)

suspend fun BehaviourScope.mineAndSell() {
    val fixedAsteroid = param("asteroid")?.uppercase()
    val fixedMarket = param("market")?.uppercase()
    val useSurveys = param("surveys")?.lowercase() != "no"
    val avoid = mutableSetOf<String>()

    while (true) {
        clock.sleep(1.seconds)
        val plan = phase("plan") {
            val ranked = Mining.rank(snapshot(), me, clock.now(), knowledge.Strategy.mining(shared.plan.phase))
                .filter { it.asteroid.symbol !in avoid }
                // One ship per rock: two lasers on one asteroid destabilize it twice as fast for the same total yield.
                .filter { !shared.claimedByOther(it.asteroid.symbol, ship) }
                .filter { fixedAsteroid == null || it.asteroid.symbol == fixedAsteroid }
                .filter { fixedMarket == null || it.market.symbol == fixedMarket }
            // The claim is the pick: two ships ranking the same rock in the same instant cannot both win the
            // put-if-absent, so the loser takes its next candidate (seen live on 2026-09-04: two drones on B11).
            val chosen = ranked.firstOrNull { shared.claim(ship, it.asteroid.symbol) } ?: throw BehaviourFailure(
                "no mineable asteroid with a buying market" + (fixedAsteroid?.let { " for $it" } ?: "") + (fixedMarket?.let { " at $it" } ?: "")
            )
            chosen
        }
        status("plan", plan.summary())

        phase("travel to asteroid", plan.asteroid.symbol) {
            ensureFuel(plan.fuelPerCycle + 10)
            travelTo(plan.asteroid.symbol)
        }

        val mined = phase("extract", "at ${plan.asteroid.symbol}") { extractUntilFull(plan, useSurveys, avoid) }

        if (me.cargo.isEmpty) {
            if (!mined) avoid += plan.asteroid.symbol
            continue
        }

        phase("travel to market", plan.market.symbol + (plan.returnLeg as? ReturnLeg.Via)?.let { " via ${it.via.symbol}" }.orEmpty()) {
            (plan.returnLeg as? ReturnLeg.Via)?.let { via ->
                travelTo(via.via.symbol)
                ensureFuel(Travel.fuelCost(via.viaToMarket, FlightMode.CRUISE))
            }
            travelTo(plan.market.symbol)
        }

        phase("sell", "at ${plan.market.symbol}") {
            dock(ship)
            val market = refreshMarket(plan.market.symbol)
            val (toSell, toDrop) = Selling.split(me.cargo, market)
            var earned = 0L
            toSell.forEach { line ->
                earned += sell(ship, line.symbol, line.units).credits
                status(detail = "sold ${line.units} ${line.symbol}; +$earned this trip")
            }
            toDrop.forEach { line -> jettison(ship, line.symbol, line.units) }
        }

        phase("refuel", "at ${plan.market.symbol}") {
            try {
                refuel(ship)
            } catch (e: VerbFailure) {
                status(detail = "could not refuel: ${e.message}")
            }
            refreshMarket(plan.market.symbol)
        }
    }
}

/** Extracts until the hold is full or the rock gives out. Returns whether anything came out. */
private suspend fun BehaviourScope.extractUntilFull(plan: MiningPlan, useSurveys: Boolean, avoid: MutableSet<String>): Boolean {
    var extracted = 0
    val wanted = plan.prices.keys
    while (!me.cargoFull) {
        var survey = if (useSurveys && me.canSurvey && !plan.asteroid.isSiphonable) Surveys.pick(surveysFor(plan.asteroid.symbol), plan) else null
        if (survey == null && useSurveys && me.canSurvey && !plan.asteroid.isSiphonable && surveysFor(plan.asteroid.symbol).isEmpty()) {
            status(detail = "surveying")
            survey(ship)
            survey = Surveys.pick(surveysFor(plan.asteroid.symbol), plan)
        }
        val got = try {
            if (plan.asteroid.isSiphonable) siphon(ship) else extract(ship, survey)
        } catch (e: VerbFailure.SurveyUnusable) {
            continue
        } catch (e: VerbFailure.AsteroidDestabilized) {
            status(detail = "destabilized; leaving")
            avoid += plan.asteroid.symbol
            break
        } catch (e: VerbFailure.NoYield) {
            status(detail = "stripped; leaving")
            avoid += plan.asteroid.symbol
            break
        }
        extracted += got.units
        if (got.good !in wanted) {
            jettison(ship, got.good, got.units)
            status(detail = "dropped ${got.units} ${got.good} (${plan.market.symbol} does not buy it); ${me.cargo.units}/${me.cargo.capacity}")
        } else {
            status(detail = "${me.cargo.units}/${me.cargo.capacity}; +${got.units} ${got.good}")
        }
        if (got.modifiers.any { it.symbol == WaypointModifiers.CRITICAL_LIMIT }) {
            status(detail = "asteroid at critical limit; leaving after this load")
            avoid += plan.asteroid.symbol
            if (me.cargo.units > 0) break
        }
    }
    return extracted > 0
}

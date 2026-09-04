package engine

import api.ApiError
import api.ApiErrorCodes
import api.GameApi
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import model.Agent
import model.Construction
import storage.SupplyRecord
import model.Shipyard
import model.actions.Survey
import model.contract.Contract
import model.market.Market
import model.market.MarketTransaction
import model.market.TradeSymbol
import model.ship.FlightMode
import model.ship.Ship
import model.ship.ShipNavStatus
import model.ship.ShipType
import model.system.OrbitalNames
import model.system.Waypoint
import storage.ExtractionRecord
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** Where the verbs report what they changed. The engine persists and publishes; a test records. */
interface VerbSink {
    suspend fun shipChanged(ship: Ship)
    suspend fun agentChanged(agent: Agent)
    suspend fun marketChanged(market: Market)
    suspend fun shipyardChanged(shipyard: Shipyard)
    suspend fun waypointChanged(waypoint: Waypoint)
    suspend fun surveysAdded(surveys: List<Survey>)
    suspend fun transaction(transaction: MarketTransaction, chain: String? = null)
    suspend fun extraction(record: ExtractionRecord)
    suspend fun statusChanged(ship: String, status: ShipStatus?, params: String)
    suspend fun contractChanged(contract: Contract, cost: Long = 0, accepted: Boolean = false, fulfilled: Boolean = false)
    suspend fun supplied(record: SupplyRecord)
    fun event(event: Event)
}

/**
 * The one [Verbs] implementation. It knows nothing about HTTP: [api] may be the real client or the
 * simulator, and the same code runs either way. Every verb re-reads the ship from the response
 * before it returns, so the world is never optimistic.
 */
class ShipVerbs(
    private val api: GameApi,
    private val world: World,
    override val clock: GameClock,
    private val sink: VerbSink,
) : Verbs {

    private var version = 0L

    override fun snapshot(): Snapshot = world.snapshot(++version)
    override fun ship(symbol: String): Ship = world.ships[symbol] ?: error("Unknown ship $symbol")
    override fun agent(): Agent = world.agent ?: error("Agent not loaded")
    override fun waypoint(symbol: String): Waypoint = world.waypoints[symbol] ?: error("Unknown waypoint $symbol")
    override fun market(symbol: String): Market? = world.markets[symbol]

    override fun surveysFor(waypoint: String): List<Survey> {
        val now = clock.now()
        world.surveys.values.filter { !it.isValidAt(now) }.forEach { world.surveys.remove(it.signature) }
        return world.surveys.values.filter { it.symbol == waypoint }.sortedBy { it.expiration }
    }

    override suspend fun orbit(ship: String): Ship {
        val current = settled(ship)
        if (current.isInOrbit) return current
        return call { api.orbit(ship) }.let { update(current.copy(nav = it.nav, fuel = it.fuel ?: current.fuel)) }
    }

    override suspend fun dock(ship: String): Ship {
        val current = settled(ship)
        if (current.isDocked) return current
        return call { api.dock(ship) }.let { update(current.copy(nav = it.nav, fuel = it.fuel ?: current.fuel)) }
    }

    override suspend fun navigateTo(ship: String, waypoint: String, mode: FlightMode): Ship {
        var current = settled(ship)
        if (current.nav.waypointSymbol == waypoint) return current
        val from = waypoint(current.nav.waypointSymbol)
        val to = waypoint(waypoint)
        val distance = Travel.distance(from.x, from.y, to.x, to.y)
        val needed = Travel.fuelCost(distance, mode)
        if (current.usesFuel && current.fuel.current < needed) {
            throw VerbFailure.InsufficientFuel(ship, needed, current.fuel.current)
        }
        if (current.isDocked) current = orbit(ship)
        if (current.nav.flightMode != mode) {
            current = call { api.setFlightMode(ship, mode) }.let { update(current.copy(nav = it.nav, fuel = it.fuel ?: current.fuel)) }
        }
        val response = try {
            call { api.navigate(ship, waypoint) }
        } catch (e: VerbFailure.Api) {
            if (e.error.code == ApiErrorCodes.NAVIGATE_INSUFFICIENT_FUEL) throw VerbFailure.InsufficientFuel(ship, needed, current.fuel.current)
            throw e
        }
        current = update(current.copy(nav = response.nav, fuel = response.fuel ?: current.fuel))
        clock.sleepUntil(response.nav.route.arrival.plusMillis(500))
        return settled(ship)
    }

    override suspend fun refuel(ship: String): Ship {
        var current = settled(ship)
        if (!current.usesFuel || current.fuel.current >= current.fuel.capacity) return current
        val here = waypoint(current.nav.waypointSymbol)
        if (!here.hasMarket) throw VerbFailure.NotAtMarket(ship, here.symbol)
        if (!current.isDocked) current = dock(ship)
        val response = call { api.refuel(ship, null) }
        sink.transaction(response.transaction, world.chainOf[ship])
        agentChanged(response.agent)
        current = update(current.copy(fuel = response.fuel, cargo = response.cargo ?: current.cargo))
        sink.event(Event.Refueled(ship, here.symbol, response.transaction.units, response.transaction.totalPrice.toLong()))
        return current
    }

    override suspend fun extract(ship: String, survey: Survey?): Extracted {
        var current = settled(ship)
        if (!current.canMine) throw VerbFailure.CannotMine(ship, "no mining laser")
        if (current.cargoFull) throw VerbFailure.CargoFull(ship)
        if (survey != null && survey.symbol != current.nav.waypointSymbol) {
            throw VerbFailure.SurveyUnusable(survey, "it is for ${survey.symbol}, ship is at ${current.nav.waypointSymbol}")
        }
        awaitCooldown(current)
        if (current.isDocked) current = orbit(ship)
        val response = try {
            call(retryOnCooldown = true) { if (survey == null) api.extract(ship) else api.extractWithSurvey(ship, survey) }
        } catch (e: VerbFailure.Api) {
            throw when (e.error.code) {
                ApiErrorCodes.EXTRACT_DESTABILIZED -> VerbFailure.AsteroidDestabilized(current.nav.waypointSymbol)
                ApiErrorCodes.WAYPOINT_NO_YIELD -> VerbFailure.NoYield(current.nav.waypointSymbol)
                ApiErrorCodes.SURVEY_EXPIRED, ApiErrorCodes.SURVEY_EXHAUSTED -> {
                    survey?.let { world.surveys.remove(it.signature) }
                    VerbFailure.SurveyUnusable(survey!!, e.error.apiMessage)
                }
                ApiErrorCodes.CARGO_FULL -> VerbFailure.CargoFull(ship)
                else -> e
            }
        }
        current = update(current.copy(cargo = response.cargo, cooldown = response.cooldown))
        val here = waypoint(current.nav.waypointSymbol)
        if (here.modifiers != response.modifiers) {
            world.waypoints[here.symbol] = here.copy(modifiers = response.modifiers)
            sink.waypointChanged(world.waypoints.getValue(here.symbol))
        }
        val yield = response.extraction.yield
        sink.extraction(ExtractionRecord(ship, here.symbol, yield.symbol, yield.units.toInt(), survey?.signature, response.modifiers.map { it.symbol }, clock.now()))
        sink.event(Event.Extracted(ship, here.symbol, yield.symbol.name, yield.units.toInt(), "${response.cargo.units}/${response.cargo.capacity}"))
        return Extracted(ship, here.symbol, yield.symbol, yield.units.toInt(), response.cargo, response.modifiers)
    }

    override suspend fun survey(ship: String): List<Survey> {
        var current = settled(ship)
        if (!current.canSurvey) throw VerbFailure.CannotMine(ship, "no surveyor mount")
        awaitCooldown(current)
        if (current.isDocked) current = orbit(ship)
        val response = call(retryOnCooldown = true) { api.survey(ship) }
        update(current.copy(cooldown = response.cooldown))
        response.surveys.forEach { world.surveys[it.signature] = it }
        sink.surveysAdded(response.surveys)
        sink.event(Event.Surveyed(ship, current.nav.waypointSymbol, response.surveys.size))
        return response.surveys
    }

    override suspend fun sell(ship: String, good: TradeSymbol, units: Int): Sale {
        var current = settled(ship)
        val here = waypoint(current.nav.waypointSymbol)
        if (!here.hasMarket) throw VerbFailure.NotAtMarket(ship, here.symbol)
        val market = world.markets[here.symbol]
        if (market != null && !market.trades(good)) throw VerbFailure.MarketRefuses(here.symbol, good)
        val have = current.unitsOf(good)
        if (have == 0 || units <= 0) return Sale(good, 0, 0, emptyList())
        if (!current.isDocked) current = dock(ship)
        val volume = market?.good(good)?.tradeVolume?.takeIf { it > 0 } ?: units
        var remaining = minOf(units, have)
        val transactions = mutableListOf<MarketTransaction>()
        while (remaining > 0) {
            val batch = minOf(remaining, volume)
            val response = try {
                call { api.sell(ship, good, batch) }
            } catch (e: VerbFailure.Api) {
                if (e.error.code == ApiErrorCodes.MARKET_NOT_SOLD) throw VerbFailure.MarketRefuses(here.symbol, good)
                throw e
            }
            transactions += response.transaction
            sink.transaction(response.transaction, world.chainOf[ship])
            agentChanged(response.agent)
            current = update(current.copy(cargo = response.cargo))
            remaining -= response.transaction.units
            if (response.transaction.units <= 0) break
        }
        val sale = Sale(good, transactions.sumOf { it.units }, transactions.sumOf { it.totalPrice.toLong() }, transactions)
        sink.event(Event.Sold(ship, here.symbol, good.name, sale.units, sale.credits))
        return sale
    }

    override suspend fun purchase(ship: String, good: TradeSymbol, units: Int): Sale {
        var current = settled(ship)
        val here = waypoint(current.nav.waypointSymbol)
        if (!here.hasMarket) throw VerbFailure.NotAtMarket(ship, here.symbol)
        val market = world.markets[here.symbol]
        if (market != null && !market.trades(good)) throw VerbFailure.MarketRefuses(here.symbol, good)
        if (units <= 0) return Sale(good, 0, 0, emptyList())
        if (!current.isDocked) current = dock(ship)
        val volume = market?.good(good)?.tradeVolume?.takeIf { it > 0 } ?: units
        var remaining = minOf(units, current.cargoSpaceLeft)
        val transactions = mutableListOf<MarketTransaction>()
        while (remaining > 0) {
            val batch = minOf(remaining, volume)
            val response = try {
                call { api.purchaseCargo(ship, good, batch) }
            } catch (e: VerbFailure.Api) {
                throw when (e.error.code) {
                    4600 -> VerbFailure.NotEnoughCredits(0, agent().credits)
                    4601, ApiErrorCodes.MARKET_NOT_SOLD -> VerbFailure.MarketRefuses(here.symbol, good)
                    4217, ApiErrorCodes.CARGO_FULL -> VerbFailure.CargoFull(ship)
                    else -> e
                }
            }
            transactions += response.transaction
            sink.transaction(response.transaction, world.chainOf[ship])
            agentChanged(response.agent)
            current = update(current.copy(cargo = response.cargo))
            remaining -= response.transaction.units
            if (response.transaction.units <= 0) break
        }
        val bought = Sale(good, transactions.sumOf { it.units }, transactions.sumOf { it.totalPrice.toLong() }, transactions)
        sink.event(Event.Bought(ship, here.symbol, good.name, bought.units, bought.credits))
        return bought
    }

    override suspend fun jettison(ship: String, good: TradeSymbol, units: Int): Ship {
        val current = settled(ship)
        val have = current.unitsOf(good)
        if (have == 0 || units <= 0) return current
        val cargo = call { api.jettison(ship, good, minOf(units, have)) }
        return update(current.copy(cargo = cargo))
    }

    override suspend fun refreshMarket(waypoint: String): Market {
        val market = call { api.getMarket(OrbitalNames.getSectorSystem(waypoint), waypoint) }
        world.markets[market.symbol] = market
        sink.marketChanged(market)
        return market
    }

    override suspend fun refreshShipyard(waypoint: String): Shipyard {
        val shipyard = call { api.getShipyard(OrbitalNames.getSectorSystem(waypoint), waypoint) }
        world.shipyards[shipyard.symbol] = shipyard
        sink.shipyardChanged(shipyard)
        return shipyard
    }

    override suspend fun purchaseShip(type: ShipType, shipyard: String): Ship {
        val yard = world.shipyards[shipyard]
        val price = yard?.priceOf(type)
        val credits = agent().credits
        if (price != null && price > credits) throw VerbFailure.NotEnoughCredits(price, credits)
        val response = call { api.purchaseShip(type, shipyard) }
        agentChanged(response.agent)
        val ship = update(response.ship)
        sink.event(Event.ShipPurchased(ship.symbol, type.name, response.transaction.price.toLong()))
        return ship
    }

    override suspend fun siphon(ship: String): Extracted {
        var current = settled(ship)
        if (!current.canSiphon) throw VerbFailure.CannotMine(ship, "no gas siphon")
        if (current.cargoFull) throw VerbFailure.CargoFull(ship)
        awaitCooldown(current)
        if (current.isDocked) current = orbit(ship)
        val response = try {
            call(retryOnCooldown = true) { api.siphon(ship) }
        } catch (e: VerbFailure.Api) {
            throw when (e.error.code) {
                ApiErrorCodes.WAYPOINT_NO_YIELD -> VerbFailure.NoYield(current.nav.waypointSymbol)
                ApiErrorCodes.CARGO_FULL -> VerbFailure.CargoFull(ship)
                else -> e
            }
        }
        current = update(current.copy(cargo = response.cargo, cooldown = response.cooldown))
        val here = waypoint(current.nav.waypointSymbol)
        val yield = response.siphon.yield
        sink.extraction(ExtractionRecord(ship, here.symbol, yield.symbol, yield.units.toInt(), null, emptyList(), clock.now()))
        sink.event(Event.Extracted(ship, here.symbol, yield.symbol.name, yield.units.toInt(), "${response.cargo.units}/${response.cargo.capacity}"))
        return Extracted(ship, here.symbol, yield.symbol, yield.units.toInt(), response.cargo, emptyList())
    }

    override suspend fun chart(ship: String): Long {
        val current = settled(ship)
        val response = call { api.chart(ship) }
        world.waypoints[response.waypoint.symbol] = response.waypoint
        sink.waypointChanged(response.waypoint)
        agentChanged(response.agent)
        sink.event(Event.Charted(ship, current.nav.waypointSymbol, response.transaction.totalPrice))
        return response.transaction.totalPrice
    }

    override suspend fun setChain(ship: String, chain: String?) {
        if (chain == null) world.chainOf.remove(ship) else world.chainOf[ship] = chain
    }

    override suspend fun construction(waypoint: String): Construction =
        call { api.getConstruction(OrbitalNames.getSectorSystem(waypoint), waypoint) }

    override suspend fun supplyConstruction(waypoint: String, ship: String, good: TradeSymbol, units: Int): Construction {
        var current = settled(ship)
        if (current.nav.waypointSymbol != waypoint) throw VerbFailure.NotAtMarket(ship, waypoint)
        if (!current.isDocked) current = dock(ship)
        val have = current.unitsOf(good)
        if (have == 0 || units <= 0) return construction(waypoint)
        val response = call { api.supplyConstruction(OrbitalNames.getSectorSystem(waypoint), waypoint, ship, good, minOf(units, have)) }
        update(current.copy(cargo = response.cargo))
        sink.supplied(SupplyRecord(ship, waypoint, good, minOf(units, have), clock.now()))
        sink.event(Event.Supplied(ship, waypoint, good.name, minOf(units, have), response.construction.remaining(good)))
        return response.construction
    }

    override fun contracts(): List<Contract> = world.contracts.values.sortedBy { it.id }

    override suspend fun negotiateContract(ship: String): Contract {
        val current = settled(ship)
        if (!current.isDocked) dock(ship)
        val response = call { api.negotiateContract(ship) }
        return remember(response.contract).also { sink.event(Event.ContractOffered(it.id, it.type, it.terms.payment.onAccepted + it.terms.payment.onFulfilled)) }
    }

    override suspend fun acceptContract(id: String): Contract {
        val response = call { api.acceptContract(id) }
        response.agent?.let { agentChanged(it) }
        world.contracts[id] = response.contract
        sink.contractChanged(response.contract, accepted = true)
        return response.contract
    }

    override suspend fun deliverContract(id: String, ship: String, good: TradeSymbol, units: Int): Contract {
        var current = settled(ship)
        if (!current.isDocked) current = dock(ship)
        val response = call { api.deliverContract(id, ship, good, units) }
        update(current.copy(cargo = response.cargo))
        world.contracts[id] = response.contract
        sink.contractChanged(response.contract)
        sink.event(Event.Delivered(ship, id, good.name, units))
        return response.contract
    }

    override suspend fun fulfillContract(id: String): Contract {
        val response = call { api.fulfillContract(id) }
        response.agent?.let { agentChanged(it) }
        world.contracts[id] = response.contract
        sink.contractChanged(response.contract, fulfilled = true)
        sink.event(Event.ContractFulfilled(id, response.contract.terms.payment.onFulfilled))
        return response.contract
    }

    private suspend fun remember(contract: Contract): Contract {
        world.contracts[contract.id] = contract
        sink.contractChanged(contract)
        return contract
    }

    override suspend fun setStatus(ship: String, status: ShipStatus?, params: String) {
        if (status == null) world.shipStatus.remove(ship) else world.shipStatus[ship] = status
        sink.statusChanged(ship, status, params)
        if (status != null) sink.event(Event.PhaseChanged(ship, status.behaviour, status.phase, status.detail))
    }

    // Internals

    /** The ship as the world knows it, with an overdue arrival applied locally. */
    private suspend fun settled(symbol: String): Ship {
        val ship = ship(symbol)
        val now = clock.now()
        if (ship.nav.status == ShipNavStatus.IN_TRANSIT) {
            if (ship.nav.inTransitAt(now)) {
                clock.sleepUntil(ship.nav.route.arrival.plusMillis(500))
            }
            val arrived = ship.copy(nav = ship.nav.copy(status = ShipNavStatus.IN_ORBIT, waypointSymbol = ship.nav.route.destination.symbol))
            return update(arrived)
        }
        return ship
    }

    private suspend fun awaitCooldown(ship: Ship) {
        ship.cooldown.expiresAt(clock.now())?.let { clock.sleepUntil(it.plusMillis(200)) }
    }

    private suspend fun update(ship: Ship): Ship {
        world.ships[ship.symbol] = ship
        sink.shipChanged(ship)
        return ship
    }

    private suspend fun agentChanged(agent: Agent) {
        world.agent = agent
        sink.agentChanged(agent)
    }

    /**
     * Runs an API call, converting [ApiError] to [VerbFailure.Api]. With [retryOnCooldown], a
     * cooldown conflict waits the time the server names and tries once more.
     */
    private suspend fun <T> call(retryOnCooldown: Boolean = false, block: suspend () -> T): T {
        try {
            return block()
        } catch (e: ApiError) {
            if (retryOnCooldown && e.code == ApiErrorCodes.COOLDOWN_CONFLICT) {
                val remaining = e.data?.jsonObject?.get("cooldown")?.jsonObject?.get("remainingSeconds")?.jsonPrimitive?.content?.toLongOrNull() ?: 1L
                logger.info { "cooldown conflict; waiting ${remaining}s" }
                clock.sleep((remaining + 1).seconds)
                try {
                    return block()
                } catch (again: ApiError) {
                    throw VerbFailure.Api(again)
                }
            }
            throw VerbFailure.Api(e)
        }
    }
}

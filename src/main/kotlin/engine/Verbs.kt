package engine

import api.ApiError
import model.Agent
import model.Construction
import model.responsebody.JumpGate
import model.actions.Survey
import model.contract.Contract
import model.market.Market
import model.market.MarketTransaction
import model.market.TradeSymbol
import model.ship.FlightMode
import model.ship.Ship
import model.ship.ShipType
import model.ship.components.Cargo
import model.system.Waypoint
import model.system.WaypointModifier
import java.time.Instant

/**
 * The vocabulary behaviours are written in. Each verb performs the call, updates the world and
 * whatever listens to it, and waits out what the game imposes (travel time, cooldowns) so that a
 * behaviour reads as a plain sequence of steps. Verbs never guess: every one re-reads the ship
 * from the server's answer, and failures come back as [VerbFailure]s with the server's reason.
 *
 * Reads are synchronous views of the current world; they never cost a request.
 */
interface Verbs {
    val clock: GameClock

    fun snapshot(): Snapshot
    fun ship(symbol: String): Ship
    fun agent(): Agent
    fun waypoint(symbol: String): Waypoint
    fun market(symbol: String): Market?

    /** Surveys known for [waypoint] that have not expired. */
    fun surveysFor(waypoint: String): List<Survey>

    suspend fun orbit(ship: String): Ship
    suspend fun dock(ship: String): Ship

    /** Returns once the ship has arrived. A no-op when already there; orbits first when docked. */
    suspend fun navigateTo(ship: String, waypoint: String, mode: FlightMode = FlightMode.CRUISE): Ship

    /** Fills the tank at the current waypoint's market. Docks first. No-op when full. */
    suspend fun refuel(ship: String): Ship

    /** Waits out any cooldown, then extracts once. Orbits first when docked. */
    suspend fun extract(ship: String, survey: Survey? = null): Extracted

    /** Waits out any cooldown, then surveys the current waypoint. Orbits first when docked. */
    suspend fun survey(ship: String): List<Survey>

    /** Sells [units] of [good] here, split into the market's trade volume. Docks first. */
    suspend fun sell(ship: String, good: TradeSymbol, units: Int): Sale

    /** Buys [units] of [good] here, split into the market's trade volume. Docks first. */
    suspend fun purchase(ship: String, good: TradeSymbol, units: Int): Sale

    suspend fun jettison(ship: String, good: TradeSymbol, units: Int): Ship

    /** Re-reads a market's prices; only meaningful with a ship at the waypoint. */
    suspend fun refreshMarket(waypoint: String): Market

    suspend fun refreshShipyard(waypoint: String): model.Shipyard

    /** Buys a ship at [shipyard]; one of ours must be there. Returns the new ship. */
    suspend fun purchaseShip(type: ShipType, shipyard: String): Ship

    /** Publishes what a behaviour is doing with [ship]; null clears it. [params] is the checkpoint payload. */
    suspend fun setStatus(ship: String, status: ShipStatus?, params: String = "{}")

    /** Waits out any cooldown, then siphons once at a gas giant. Orbits first when docked. */
    suspend fun siphon(ship: String): Extracted

    /** Charts the waypoint the ship is at; returns the reward. */
    suspend fun chart(ship: String): Long

    /** Tags [ship]'s transactions with a chain id from now on; null clears it. */
    suspend fun setChain(ship: String, chain: String?)

    /** A gate's connections; one request. */
    suspend fun jumpGate(waypoint: String): JumpGate

    /** Jumps [ship] through the gate it is at to [waypoint], buying the antimatter locally. Returns on arrival. */
    suspend fun jump(ship: String, waypoint: String): Ship

    /** What a construction site still needs; one request. */
    suspend fun construction(waypoint: String): Construction

    /** Hands [units] of [good] from [ship]'s hold to the site. Docks first. */
    suspend fun supplyConstruction(waypoint: String, ship: String, good: TradeSymbol, units: Int): Construction

    fun contracts(): List<Contract>
    suspend fun negotiateContract(ship: String): Contract
    suspend fun acceptContract(id: String): Contract
    suspend fun deliverContract(id: String, ship: String, good: TradeSymbol, units: Int): Contract
    suspend fun fulfillContract(id: String): Contract
}

data class Extracted(
    val ship: String,
    val waypoint: String,
    val good: TradeSymbol,
    val units: Int,
    val cargo: Cargo,
    /** The asteroid's modifiers after this extraction; UNSTABLE and worse mean it is wearing out. */
    val modifiers: List<WaypointModifier>,
)

data class Sale(
    val good: TradeSymbol,
    val units: Int,
    val credits: Long,
    val transactions: List<MarketTransaction>,
) {
    val averagePrice: Double get() = if (units == 0) 0.0 else credits.toDouble() / units
}

/** A verb could not do what was asked, for a reason the behaviour may want to act on. */
sealed class VerbFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class InsufficientFuel(val ship: String, val needed: Long, val available: Long) :
        VerbFailure("$ship needs $needed fuel but has $available")

    class CargoFull(val ship: String) : VerbFailure("$ship's cargo is full")

    class NotAtMarket(val ship: String, val waypoint: String) : VerbFailure("$ship is at $waypoint, which has no market")

    class MarketRefuses(val waypoint: String, val good: TradeSymbol) : VerbFailure("$waypoint does not trade $good")

    class AsteroidDestabilized(val waypoint: String) : VerbFailure("$waypoint is destabilized; extraction refused")

    class NoYield(val waypoint: String) : VerbFailure("$waypoint yields nothing")

    class SurveyUnusable(val survey: Survey, val reason: String) : VerbFailure("survey ${survey.signature} unusable: $reason")

    class CannotMine(val ship: String, val reason: String) : VerbFailure("$ship cannot mine: $reason")

    class NotEnoughCredits(val needed: Long, val available: Long) : VerbFailure("need $needed credits, have $available")

    class Api(val error: ApiError) : VerbFailure(error.message ?: "API error", error)
}

/** What a behaviour is doing right now, for `ships` and the dashboard. */
data class ShipStatus(
    val behaviour: String,
    val phase: String,
    val detail: String,
    val since: Instant,
)

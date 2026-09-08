package api

import model.Agent
import model.Construction
import model.SupplyConstructionResponse
import model.ServerStatus
import model.Shipyard
import model.actions.Survey
import model.market.Market
import model.market.TradeSymbol
import model.contract.Contract
import model.responsebody.BuySellCargoResponse
import model.responsebody.ChartResponse
import model.responsebody.ContractResponse
import model.responsebody.DeliverResponse
import model.responsebody.JumpGate
import model.responsebody.JumpResponse
import model.responsebody.SiphonResponse
import model.responsebody.ExtractionResponse
import model.responsebody.NavigationResponse
import model.responsebody.RefuelResponse
import model.responsebody.ShipPurchaseResponse
import model.responsebody.SurveyResponse
import model.ship.FlightMode
import model.ship.Ship
import model.ship.ShipType
import model.ship.components.Cargo
import model.system.System
import model.system.Waypoint

/**
 * The game as a set of calls: what the verb layer needs from the server. [SpaceTradersApi] is the
 * real thing over HTTP; `sim.SimApi` is the simulator answering the same calls in memory. Both
 * fail with [ApiError] carrying the server's codes, so the verbs treat them identically.
 */
interface GameApi {
    suspend fun getStatus(): ServerStatus

    /** Every agent on the server, public details only: symbol, headquarters, credits, ships. Twenty a request. */
    suspend fun listAgents(): List<model.PublicAgent>
    suspend fun getMyAgent(): Agent
    suspend fun getSystem(symbol: String): System
    suspend fun listSystemWaypoints(system: String): List<Waypoint>
    suspend fun getMarket(system: String, waypoint: String): Market
    suspend fun getShipyard(system: String, waypoint: String): Shipyard
    suspend fun listMyShips(): List<Ship>
    suspend fun getMyShip(symbol: String): Ship

    suspend fun orbit(ship: String): NavigationResponse
    suspend fun dock(ship: String): NavigationResponse
    suspend fun navigate(ship: String, waypoint: String): NavigationResponse
    /** Warp to a waypoint in another system; the ship needs a warp drive and fuel for the distance between the systems. */
    suspend fun warp(ship: String, waypoint: String): NavigationResponse
    suspend fun setFlightMode(ship: String, mode: FlightMode): NavigationResponse
    suspend fun extract(ship: String): ExtractionResponse
    suspend fun extractWithSurvey(ship: String, survey: Survey): ExtractionResponse
    suspend fun survey(ship: String): SurveyResponse
    suspend fun refuel(ship: String, units: Int? = null): RefuelResponse
    suspend fun sell(ship: String, symbol: TradeSymbol, units: Int): BuySellCargoResponse
    suspend fun purchaseCargo(ship: String, symbol: TradeSymbol, units: Int): BuySellCargoResponse
    suspend fun jettison(ship: String, symbol: TradeSymbol, units: Int): Cargo
    /** Moves cargo between two of our ships at the same waypoint, both docked or both in orbit. */
    suspend fun transfer(fromShip: String, toShip: String, symbol: TradeSymbol, units: Int): model.responsebody.TransferResponse
    suspend fun purchaseShip(type: ShipType, waypoint: String): ShipPurchaseResponse

    suspend fun siphon(ship: String): SiphonResponse
    suspend fun chart(ship: String): ChartResponse

    suspend fun listContracts(): List<Contract>
    suspend fun negotiateContract(ship: String): ContractResponse
    suspend fun acceptContract(id: String): ContractResponse
    suspend fun deliverContract(id: String, ship: String, symbol: TradeSymbol, units: Int): DeliverResponse
    suspend fun fulfillContract(id: String): ContractResponse

    suspend fun getJumpGate(system: String, waypoint: String): JumpGate
    suspend fun jump(ship: String, waypoint: String): JumpResponse

    suspend fun getConstruction(system: String, waypoint: String): Construction
    suspend fun supplyConstruction(system: String, waypoint: String, ship: String, symbol: TradeSymbol, units: Int): SupplyConstructionResponse
}

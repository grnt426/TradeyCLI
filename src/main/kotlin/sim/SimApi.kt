package sim

import api.GameApi
import model.Agent
import model.ServerStatus
import model.Shipyard
import model.actions.Survey
import model.market.Market
import model.market.TradeSymbol
import model.responsebody.BuySellCargoResponse
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

/** The simulator answering [GameApi] directly, with no HTTP in between. */
class SimApi(val universe: SimUniverse) : GameApi {
    override suspend fun getStatus(): ServerStatus = universe.status()
    override suspend fun getMyAgent(): Agent = universe.agent()
    override suspend fun getSystem(symbol: String): System = universe.system(symbol)
    override suspend fun listSystemWaypoints(system: String): List<Waypoint> = universe.listWaypoints(system)
    override suspend fun getMarket(system: String, waypoint: String): Market = universe.market(waypoint)
    override suspend fun getShipyard(system: String, waypoint: String): Shipyard = universe.shipyard(waypoint)
    override suspend fun listMyShips(): List<Ship> = universe.listShips()
    override suspend fun getMyShip(symbol: String): Ship = universe.ship(symbol)
    override suspend fun orbit(ship: String): NavigationResponse = universe.orbit(ship)
    override suspend fun dock(ship: String): NavigationResponse = universe.dock(ship)
    override suspend fun navigate(ship: String, waypoint: String): NavigationResponse = universe.navigate(ship, waypoint)
    override suspend fun setFlightMode(ship: String, mode: FlightMode): NavigationResponse = universe.setFlightMode(ship, mode)
    override suspend fun extract(ship: String): ExtractionResponse = universe.extract(ship, null)
    override suspend fun extractWithSurvey(ship: String, survey: Survey): ExtractionResponse = universe.extract(ship, survey)
    override suspend fun survey(ship: String): SurveyResponse = universe.survey(ship)
    override suspend fun refuel(ship: String, units: Int?): RefuelResponse = universe.refuel(ship, units)
    override suspend fun sell(ship: String, symbol: TradeSymbol, units: Int): BuySellCargoResponse = universe.sell(ship, symbol, units)
    override suspend fun jettison(ship: String, symbol: TradeSymbol, units: Int): Cargo = universe.jettison(ship, symbol, units)
    override suspend fun purchaseShip(type: ShipType, waypoint: String): ShipPurchaseResponse = universe.purchaseShip(type, waypoint)
}

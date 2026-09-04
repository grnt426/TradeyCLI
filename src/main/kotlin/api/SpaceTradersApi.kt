package api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import model.Agent
import model.Construction
import model.SupplyConstructionResponse
import model.requestbody.SupplyConstructionRequest
import model.ApiJson
import model.ServerStatus
import model.Shipyard
import model.actions.Survey
import model.faction.FactionSymbol
import model.market.Market
import model.market.TradeSymbol
import model.contract.Contract
import model.requestbody.CargoTransferRequest
import model.requestbody.ContractDeliverRequest
import model.requestbody.FlightModeRequest
import model.requestbody.JettisonRequest
import model.requestbody.RefuelRequest
import model.requestbody.RegisterRequest
import model.requestbody.SellCargoRequest
import model.requestbody.ShipPurchaseRequest
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
import model.responsebody.TransferResponse
import model.ship.FlightMode
import model.ship.Ship
import model.ship.ShipType
import model.ship.components.Cargo
import model.system.System
import model.system.Waypoint
import model.system.WaypointSymbol

/**
 * Typed bindings for the endpoints the client uses. Paths and bodies match
 * api-docs/live/openapi-bundled.json; responses decode into the model classes, which the model
 * drift test keeps aligned with the spec.
 */
class SpaceTradersApi(val client: ApiClient) : GameApi {

    // Reads

    /** Server status and, importantly, the date of the current reset. Needs no token. */
    override suspend fun getStatus(): ServerStatus =
        client.getRoot("", Priority.INTERACTIVE).decode()

    override suspend fun getMyAgent(): Agent =
        client.get("my/agent", Priority.INTERACTIVE).decode()

    override suspend fun getSystem(symbol: String): System =
        client.get("systems/$symbol", Priority.INTERACTIVE).decode()

    /** Every waypoint of a system, twenty per request. */
    override suspend fun listSystemWaypoints(system: String): List<Waypoint> =
        client.getAll("systems/$system/waypoints", Priority.BACKGROUND).map { it.decode<Waypoint>() }

    suspend fun getWaypoint(system: String, waypoint: String): Waypoint =
        client.get("systems/$system/waypoints/$waypoint", Priority.BACKGROUND).decode()

    override suspend fun getMarket(system: String, waypoint: String): Market =
        client.get("systems/$system/waypoints/$waypoint/market", Priority.BACKGROUND).decode()

    override suspend fun getShipyard(system: String, waypoint: String): Shipyard =
        client.get("systems/$system/waypoints/$waypoint/shipyard", Priority.BACKGROUND).decode()

    override suspend fun listMyShips(): List<Ship> =
        client.getAll("my/ships", Priority.INTERACTIVE).map { it.decode<Ship>() }

    override suspend fun getMyShip(symbol: String): Ship =
        client.get("my/ships/$symbol", Priority.INTERACTIVE).decode()

    // Registration (needs a client built with an account token)

    /** Raw `data` of the register call, so the caller can save the token before decoding the rest. */
    suspend fun register(symbol: String, faction: FactionSymbol): JsonElement =
        client.post("register", RegisterRequest(symbol, faction), Priority.INTERACTIVE)

    // Ship actions

    override suspend fun orbit(ship: String): NavigationResponse =
        client.post("my/ships/$ship/orbit", Priority.ACTION).decode()

    override suspend fun dock(ship: String): NavigationResponse =
        client.post("my/ships/$ship/dock", Priority.ACTION).decode()

    override suspend fun navigate(ship: String, waypoint: String): NavigationResponse =
        client.post("my/ships/$ship/navigate", WaypointSymbol(waypoint), Priority.ACTION).decode()

    override suspend fun setFlightMode(ship: String, mode: FlightMode): NavigationResponse =
        client.patch("my/ships/$ship/nav", FlightModeRequest(mode), Priority.ACTION).decode()

    override suspend fun extract(ship: String): ExtractionResponse =
        client.post("my/ships/$ship/extract", Priority.ACTION).decode()

    /** The survey goes back exactly as it came; the server validates its signature. */
    override suspend fun extractWithSurvey(ship: String, survey: Survey): ExtractionResponse =
        client.post("my/ships/$ship/extract/survey", survey, Priority.ACTION).decode()

    override suspend fun survey(ship: String): SurveyResponse =
        client.post("my/ships/$ship/survey", Priority.ACTION).decode()

    override suspend fun refuel(ship: String, units: Int?): RefuelResponse =
        client.post("my/ships/$ship/refuel", RefuelRequest(units), Priority.ACTION).decode()

    override suspend fun sell(ship: String, symbol: TradeSymbol, units: Int): BuySellCargoResponse =
        client.post("my/ships/$ship/sell", SellCargoRequest(symbol, units), Priority.ACTION).decode()

    override suspend fun purchaseCargo(ship: String, symbol: TradeSymbol, units: Int): BuySellCargoResponse =
        client.post("my/ships/$ship/purchase", SellCargoRequest(symbol, units), Priority.ACTION).decode()

    override suspend fun jettison(ship: String, symbol: TradeSymbol, units: Int): Cargo =
        client.post("my/ships/$ship/jettison", JettisonRequest(symbol, units), Priority.ACTION)
            .jsonObject.getValue("cargo").decode()

    suspend fun transfer(fromShip: String, toShip: String, symbol: TradeSymbol, units: Int): TransferResponse =
        client.post("my/ships/$fromShip/transfer", CargoTransferRequest(symbol, units, toShip), Priority.ACTION).decode()

    override suspend fun purchaseShip(type: ShipType, waypoint: String): ShipPurchaseResponse =
        client.post("my/ships", ShipPurchaseRequest(type, waypoint), Priority.ACTION).decode()

    override suspend fun siphon(ship: String): SiphonResponse =
        client.post("my/ships/$ship/siphon", Priority.ACTION).decode()

    override suspend fun chart(ship: String): ChartResponse =
        client.post("my/ships/$ship/chart", Priority.ACTION).decode()

    // Contracts

    override suspend fun listContracts(): List<Contract> =
        client.getAll("my/contracts", Priority.INTERACTIVE).map { it.decode<Contract>() }

    override suspend fun negotiateContract(ship: String): ContractResponse =
        client.post("my/ships/$ship/negotiate/contract", Priority.ACTION).decode()

    override suspend fun acceptContract(id: String): ContractResponse =
        client.post("my/contracts/$id/accept", Priority.ACTION).decode()

    override suspend fun deliverContract(id: String, ship: String, symbol: TradeSymbol, units: Int): DeliverResponse =
        client.post("my/contracts/$id/deliver", ContractDeliverRequest(ship, symbol.name, units), Priority.ACTION).decode()

    override suspend fun fulfillContract(id: String): ContractResponse =
        client.post("my/contracts/$id/fulfill", Priority.ACTION).decode()

    // Jump gates

    override suspend fun getJumpGate(system: String, waypoint: String): JumpGate =
        client.get("systems/$system/waypoints/$waypoint/jump-gate", Priority.INTERACTIVE).decode()

    override suspend fun jump(ship: String, waypoint: String): JumpResponse =
        client.post("my/ships/$ship/jump", WaypointSymbol(waypoint), Priority.ACTION).decode()

    // Construction

    override suspend fun getConstruction(system: String, waypoint: String): Construction =
        client.get("systems/$system/waypoints/$waypoint/construction", Priority.ACTION).decode()

    override suspend fun supplyConstruction(system: String, waypoint: String, ship: String, symbol: TradeSymbol, units: Int): SupplyConstructionResponse =
        client.post("systems/$system/waypoints/$waypoint/construction/supply", SupplyConstructionRequest(ship, symbol.name, units), Priority.ACTION).decode()
}

inline fun <reified T> JsonElement.decode(): T = ApiJson.decodeFromJsonElement(this)

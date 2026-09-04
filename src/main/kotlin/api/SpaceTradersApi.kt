package api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import model.Agent
import model.ApiJson
import model.Shipyard
import model.market.Market
import model.market.TradeSymbol
import model.requestbody.CargoTransferRequest
import model.requestbody.JettisonRequest
import model.requestbody.RegisterRequest
import model.requestbody.SellCargoRequest
import model.requestbody.ShipPurchaseRequest
import model.responsebody.BuySellCargoResponse
import model.responsebody.ExtractionResponse
import model.responsebody.NavigationResponse
import model.responsebody.RefuelResponse
import model.responsebody.ShipPurchaseResponse
import model.responsebody.TransferResponse
import model.faction.FactionSymbol
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
class SpaceTradersApi(val client: ApiClient) {

    // Reads

    suspend fun getMyAgent(): Agent =
        client.get("my/agent", Priority.INTERACTIVE).decode()

    suspend fun getSystem(symbol: String): System =
        client.get("systems/$symbol", Priority.INTERACTIVE).decode()

    /** Every waypoint of a system, twenty per request. */
    suspend fun listSystemWaypoints(system: String): List<Waypoint> =
        client.getAll("systems/$system/waypoints", Priority.BACKGROUND).map { it.decode<Waypoint>() }

    suspend fun getWaypoint(system: String, waypoint: String): Waypoint =
        client.get("systems/$system/waypoints/$waypoint", Priority.BACKGROUND).decode()

    suspend fun getMarket(system: String, waypoint: String): Market =
        client.get("systems/$system/waypoints/$waypoint/market", Priority.BACKGROUND).decode()

    suspend fun getShipyard(system: String, waypoint: String): Shipyard =
        client.get("systems/$system/waypoints/$waypoint/shipyard", Priority.BACKGROUND).decode()

    suspend fun listMyShips(): List<Ship> =
        client.getAll("my/ships", Priority.INTERACTIVE).map { it.decode<Ship>() }

    suspend fun getMyShip(symbol: String): Ship =
        client.get("my/ships/$symbol", Priority.INTERACTIVE).decode()

    // Registration (needs a client built with an account token)

    /** Raw `data` of the register call, so the caller can save the token before decoding the rest. */
    suspend fun register(symbol: String, faction: FactionSymbol): JsonElement =
        client.post("register", RegisterRequest(symbol, faction), Priority.INTERACTIVE)

    // Ship actions

    suspend fun orbit(ship: String): NavigationResponse =
        client.post("my/ships/$ship/orbit", Priority.ACTION).decode()

    suspend fun dock(ship: String): NavigationResponse =
        client.post("my/ships/$ship/dock", Priority.ACTION).decode()

    suspend fun navigate(ship: String, waypoint: String): NavigationResponse =
        client.post("my/ships/$ship/navigate", WaypointSymbol(waypoint), Priority.ACTION).decode()

    suspend fun extract(ship: String): ExtractionResponse =
        client.post("my/ships/$ship/extract", Priority.ACTION).decode()

    suspend fun refuel(ship: String): RefuelResponse =
        client.post("my/ships/$ship/refuel", Priority.ACTION).decode()

    suspend fun sell(ship: String, symbol: TradeSymbol, units: Int): BuySellCargoResponse =
        client.post("my/ships/$ship/sell", SellCargoRequest(symbol, units), Priority.ACTION).decode()

    suspend fun purchaseCargo(ship: String, symbol: TradeSymbol, units: Int): BuySellCargoResponse =
        client.post("my/ships/$ship/purchase", SellCargoRequest(symbol, units), Priority.ACTION).decode()

    suspend fun jettison(ship: String, symbol: TradeSymbol, units: Int): Cargo =
        client.post("my/ships/$ship/jettison", JettisonRequest(symbol, units), Priority.ACTION)
            .jsonObject.getValue("cargo").decode()

    suspend fun transfer(fromShip: String, toShip: String, symbol: TradeSymbol, units: Int): TransferResponse =
        client.post("my/ships/$fromShip/transfer", CargoTransferRequest(symbol, units, toShip), Priority.ACTION).decode()

    suspend fun purchaseShip(type: ShipType, waypoint: String): ShipPurchaseResponse =
        client.post("my/ships", ShipPurchaseRequest(type, waypoint), Priority.ACTION).decode()
}

inline fun <reified T> JsonElement.decode(): T = ApiJson.decodeFromJsonElement(this)

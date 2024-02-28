package model

import client.SpaceTradersClient
import client.SpaceTradersClient.callGet
import client.SpaceTradersClient.ignoredFailback
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import model.extension.LastRead
import model.market.MarketTransaction
import model.requestbody.ShipPurchaseRequest
import model.responsebody.ShipPurchaseResponse
import model.ship.PurchasableShip
import model.ship.ShipType
import model.ship.ShipTypeContainer
import kotlin.reflect.KSuspendFunction1

@Serializable
data class Shipyard(
    val symbol: String,
    val shipTypes: List<ShipTypeContainer>,
    val modificationsFee: Long,

    val transactions: List<MarketTransaction> = mutableListOf(),
    val ships: List<PurchasableShip> = mutableListOf(),
): LastRead()

fun fetchShipyardData(system: String, waypoint: String): Shipyard? = callGet<Shipyard>(request {
    url(api("systems/$system/waypoints/$waypoint/shipyard"))
})

fun findShipyardsSellingShipType(system: String, typeContainer: ShipType): List<Shipyard> =
    GameState.shipyards.values.filter { s -> s.shipTypes.any { t -> t.type == typeContainer } }

fun purchaseShip(shipType: ShipType, waypoint: String, cb: KSuspendFunction1<ShipPurchaseResponse, Unit>) {
    SpaceTradersClient.enqueueRequest<ShipPurchaseResponse>(
        cb,
        ::ignoredFailback,
        request {
            url(api("my/ships"))
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(ShipPurchaseRequest(shipType, waypoint))
        }
    )
}

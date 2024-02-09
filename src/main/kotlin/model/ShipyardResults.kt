package model

import client.SpaceTradersClient.callGet
import io.ktor.client.request.*
import model.ship.PurchasableShip
import model.ship.ShipTypeContainer
import kotlinx.serialization.Serializable
import model.market.MarketTransaction

@Serializable
data class ShipyardResults(
    val symbol: String,
    val shipTypes: List<ShipTypeContainer>,
    val transactions: List<MarketTransaction>,
    val ships: List<PurchasableShip>,
    val modificationsFee: Long,
): LastRead()

fun fetchShipyardData(system: String, waypoint: String): ShipyardResults? = callGet<ShipyardResults>(request {
    url(api("systems/$system/waypoints/$waypoint/shipyard"))
})
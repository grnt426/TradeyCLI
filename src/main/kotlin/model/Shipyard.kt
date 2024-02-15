package model

import client.SpaceTradersClient.callGet
import io.ktor.client.request.*
import kotlinx.serialization.Serializable
import model.extension.LastRead
import model.market.MarketTransaction
import model.ship.PurchasableShip
import model.ship.ShipTypeContainer

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
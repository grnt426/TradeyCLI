package model

import client.SpaceTradersClient.callGet
import io.ktor.client.request.*
import model.ship.PurchasableShip
import model.ship.ShipTypeContainer
import kotlinx.serialization.Serializable
import model.market.MarketTransaction

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
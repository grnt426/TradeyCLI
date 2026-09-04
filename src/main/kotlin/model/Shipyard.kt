package model

import kotlinx.serialization.Serializable
import model.extension.LastRead
import model.market.ShipyardTransaction
import model.ship.PurchasableShip
import model.ship.ShipType
import model.ship.ShipTypeContainer

@Serializable
data class Shipyard(
    val symbol: String,
    val shipTypes: List<ShipTypeContainer>,
    val modificationsFee: Long,

    val transactions: List<ShipyardTransaction> = emptyList(),

    /** Present only when one of our ships is at the waypoint. */
    val ships: List<PurchasableShip> = emptyList(),
) : LastRead() {
    fun sells(type: ShipType): Boolean = shipTypes.any { it.type == type }
    fun priceOf(type: ShipType): Long? = ships.firstOrNull { it.type == type }?.purchasePrice
}

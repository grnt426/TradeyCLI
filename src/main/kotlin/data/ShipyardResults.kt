package data

import data.ship.PurchasableShip
import data.ship.ShipTypeContainer
import kotlinx.serialization.Serializable

@Serializable
data class ShipyardResults(
    val symbol: String,
    val shipTypes: List<ShipTypeContainer>,
    val transactions: List<Transaction>,
    val ships: List<PurchasableShip>,
    val modificationsFee: Long,
)

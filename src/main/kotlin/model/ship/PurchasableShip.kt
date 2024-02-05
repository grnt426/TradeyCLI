package model.ship

import client.SpaceTradersClient
import model.ship.components.*
import model.system.Waypoint
import io.ktor.client.request.*
import kotlinx.serialization.Serializable

@Serializable
data class PurchasableShip(
    val crew: Crew,
    val frame: Frame,
    val engine: Engine,
    val reactor: Reactor,
    val modules: List<Module>,
    val mounts: List<Mount>,
    val supply: String,
    val purchasePrice: Long,
    val type: ShipType,
    val name: String,
    val description: String,
)

fun shipsForPurchase(waypoint: Waypoint) = SpaceTradersClient.callFetch<List<PurchasableShip>>( request {
    url("https://api.spacetraders.io/v2/systems/X1-GH12/waypoints/${waypoint.symbol.uppercase()}/shipyard")
})
package model.ship

import kotlinx.serialization.Serializable
import model.ship.components.*

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
    val activity: String? = null,
)

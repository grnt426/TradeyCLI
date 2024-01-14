package data.ship

import data.ship.components.*
import kotlinx.serialization.Serializable

@Serializable
data class Ship(
    val symbol: String,
    val nav: Navigation,
    val crew: Crew,
    val fuel: Fuel,
    val cooldown: Cooldown,
    val frame: Frame,
    val engine: Engine,
    val reactor: Reactor,
    val modules: List<data.ship.components.Module>,
    val mounts: List<Mount>,
    val registration: Registration,
    val cargo: Cargo,
)

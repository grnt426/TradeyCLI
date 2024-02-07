package model.ship

import Symbol
import client.SpaceTradersClient
import client.SpaceTradersClient.callGet
import io.ktor.client.request.*
import model.ship.components.*
import kotlinx.serialization.Serializable
import model.api

@Serializable
data class Ship(
    override val symbol: String,
    val nav: Navigation,
    val crew: Crew,
    val fuel: Fuel,
    var cooldown: Cooldown,
    val frame: Frame,
    val engine: Engine,
    val reactor: Reactor,
    val modules: List<Module>,
    val mounts: List<Mount>,
    val registration: Registration,
    var cargo: Cargo,
): Symbol

fun listShips(): List<Ship>? = callGet<List<Ship>>(request {
    url(api("my/ships"))
})
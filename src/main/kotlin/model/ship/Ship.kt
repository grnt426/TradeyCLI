package model.ship

import Symbol
import client.SpaceTradersClient
import client.SpaceTradersClient.callGet
import client.SpaceTradersClient.ignoredCallback
import client.SpaceTradersClient.ignoredFailback
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import model.ship.components.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import model.GameState
import model.actions.Extract
import model.api
import responsebody.RefuelResponse
import script.MessageableScriptExecutor
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

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

fun getShips(): List<Ship> = GameState.ships.values.toList()

fun applyExtractResults(ship: Ship, extract: Extract) {
    ship.cargo = extract.cargo
    ship.cooldown = extract.cooldown
}

/**
 * If the ship is not already in orbit, will enqueue a request to orbit.
 * By default, will ignore the callbacks for simplicity. This will mean
 * the caller won't know it fails if that's important.
 *
 * @return true if a request to move into orbit was sent. Otherwise false, meaning
 * already in orbit.
 */
fun toOrbit(
    ship: Ship,
    cb: KSuspendFunction1<Navigation, Unit> = ::ignoredCallback,
    fb: KSuspendFunction2<HttpResponse?, Exception?, Unit> = ::ignoredFailback
): Boolean {
    if (ship.nav.status != ShipNavStatus.IN_ORBIT) {
        SpaceTradersClient.enqueueRequest<Navigation>(
            cb,
            fb,
            request {
                url(api("my/ships/${ship.symbol}/orbit"))
                method = HttpMethod.Post
            }
        )

        return true
    }

    return false
}

/**
 * If the ship is not already docked, will enqueue a request to dock.
 * By default, will ignore the callbacks for simplicity. This will mean
 * the caller won't know it fails if that's important.
 *
 * @return true if a request to dock was sent. Otherwise false, meaning
 * already docked.
 */
fun toDock(
    ship: Ship,
    cb: KSuspendFunction1<Navigation, Unit> = ::ignoredCallback,
    fb: KSuspendFunction2<HttpResponse?, Exception?, Unit> = ::ignoredFailback
): Boolean {
    if (ship.nav.status != ShipNavStatus.DOCKED) {
        SpaceTradersClient.enqueueRequest<Navigation>(
            cb,
            fb,
            request {
                url(api("my/ships/${ship.symbol}/dock"))
                method = HttpMethod.Post
            }
        )

        return true
    }

    return false
}

fun hasfuelRatio(ship: Ship, ratio: Double): Boolean = hasfuelRatio(ship.fuel, ratio)
fun hasfuelRatio(fuel: Fuel, ratio: Double): Boolean = fuel.current / fuel.capacity > ratio

fun shipsAtSameWaypoint(first: Ship, second: Ship): Boolean = first.nav.waypointSymbol == second.nav.waypointSymbol
fun shipsInSameSystem(first: Ship, second: Ship): Boolean = first.nav.systemSymbol == second.nav.systemSymbol

fun buyFuel(ship: Ship) {
    if (ship.fuel.consumed.amount != 0L) {
        SpaceTradersClient.enqueueRequest<RefuelResponse>(
            ::ignoredCallback,
            ::ignoredFailback,
            request {
                url(api("my/ships/${ship.symbol}/refuel"))
                method = HttpMethod.Post
            }
        )
    }
}
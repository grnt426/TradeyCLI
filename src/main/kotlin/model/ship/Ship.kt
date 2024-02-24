package model.ship

import Symbol
import client.SpaceTradersClient
import client.SpaceTradersClient.callGet
import client.SpaceTradersClient.ignoredCallback
import client.SpaceTradersClient.ignoredFailback
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import model.GameState
import model.api
import model.extension.LastRead
import model.market.TradeSymbol
import model.requestbody.JettisonRequest
import model.responsebody.ExtractionResponse
import model.responsebody.NavigationResponse
import model.responsebody.RefuelResponse
import model.ship.components.*
import script.ScriptExecutor
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

@Serializable
data class Ship(
    override val symbol: String,
    var nav: Navigation,
    val crew: Crew,
    var fuel: Fuel,
    var cooldown: Cooldown,
    val frame: Frame,
    val engine: Engine,
    val reactor: Reactor,
    val modules: List<Module>,
    val mounts: List<Mount>,
    val registration: Registration,
    var cargo: Cargo,

    @Transient var script: ScriptExecutor<*>? = null
) : Symbol, LastRead()

fun listShips(): List<Ship>? = callGet<List<Ship>>(request {
    url(api("my/ships"))
})

fun getShips(): List<Ship> = GameState.ships.values.toList()

fun applyExtractResults(ship: Ship, extract: ExtractionResponse) {
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
    cb: KSuspendFunction1<NavigationResponse, Unit> = ::ignoredCallback,
    fb: KSuspendFunction2<HttpResponse?, Exception?, Unit> = ::ignoredFailback
): Boolean {
    if (ship.nav.status != ShipNavStatus.IN_ORBIT) {
        SpaceTradersClient.enqueueRequest<NavigationResponse>(
            cb,
            fb,
            request {
                url(api("my/ships/${ship.symbol}/orbit"))
                method = HttpMethod.Post
            }
        )
        ship.nav.status = ShipNavStatus.IN_ORBIT
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
    cb: KSuspendFunction1<NavigationResponse, Unit> = ::ignoredCallback,
    fb: KSuspendFunction2<HttpResponse?, Exception?, Unit> = ::ignoredFailback
): Boolean {
    if (ship.nav.status != ShipNavStatus.DOCKED) {
        println("Making dock request")
        SpaceTradersClient.enqueueRequest<NavigationResponse>(
            cb,
            fb,
            request {
                url(api("my/ships/${ship.symbol}/dock"))
                method = HttpMethod.Post
            }
        )
        ship.nav.status = ShipNavStatus.DOCKED
        return true
    }

    return false
}

fun hasfuelRatio(ship: Ship, ratio: Double): Boolean = hasfuelRatio(ship.fuel, ratio)

fun shipsAtSameWaypoint(first: Ship, second: Ship): Boolean = first.nav.waypointSymbol == second.nav.waypointSymbol
fun shipsInSameSystem(first: Ship, second: Ship): Boolean = first.nav.systemSymbol == second.nav.systemSymbol

fun refuel(ship: Ship) = buyFuel(ship)
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

fun hasCargoRatio(ship: Ship, ratio: Double): Boolean = hasCargoRatio(ship.cargo, ratio)
fun cargoFull(ship: Ship): Boolean = cargoFull(ship.cargo)
fun cargoNotFull(ship: Ship): Boolean = cargoNotFull(ship.cargo)

fun hasCargo(ship: Ship): Boolean = hasCargo(ship.cargo)
fun cargoEmpty(ship: Ship): Boolean = cargoEmpty(ship.cargo)
fun cargoSpaceLeft(ship: Ship): Int = cargoSpaceLeft(ship.cargo)
fun findInventoryOfSizeMax(ship: Ship, size: Int): List<Inventory> = findInventoryOfSizeMax(ship.cargo, size)

fun removeLocalCargo(ship: Ship, inventory: Inventory): Inventory = removeLocalCargo(ship.cargo, inventory)
fun removeLocalCargo(ship: Ship, good: TradeSymbol): Inventory = removeLocalCargo(ship.cargo, good)
fun jettisonCargo(ship: Ship, inventory: Inventory) {
    SpaceTradersClient.enqueueFafRequest(
        request {
            url(api("my/ships/${ship.symbol}/jettison"))
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(JettisonRequest(inventory.symbol, inventory.units))
        }
    )
}

fun jettisonCargo(ship: Ship, candidates: List<TradeSymbol>) {
    ship.cargo.inventory
        .filter { i -> candidates.contains(i.symbol) }
        .forEach { i ->
            jettisonCargo(ship, i)
            ship.cargo.units -= i.units
            ship.cargo.inventory.remove(i)
        }
}

fun systemOf(ship: Ship): String = ship.nav.systemSymbol

fun isNavigating(ship: Ship): Boolean = isNavigating(ship.nav)
fun isOrbiting(ship: Ship): Boolean = isOrbiting(ship.nav)
fun isDocked(ship: Ship): Boolean = isDocked(ship.nav)

fun shipById(symbol: String): Ship? = GameState.ships[symbol]
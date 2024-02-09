package model.ship.components

import client.SpaceTradersClient
import client.SpaceTradersClient.ignoredCallback
import client.SpaceTradersClient.ignoredFailback
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import model.ship.Ship
import kotlinx.serialization.Serializable
import model.TradeSymbol
import model.api
import model.ship.Navigation
import model.system.WaypointSymbol
import requestbody.CargoTransferRequest
import requestbody.SellCargoRequest
import responsebody.SellCargoResponse
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

@Serializable
data class Cargo(
    val capacity: Int,
    var units: Int,
    val inventory: MutableList<Inventory>
)

fun hasCargoRatio(cargo: Cargo, ratio: Double): Boolean =
    if(cargo.capacity == 0) false
    else cargo.units / cargo.capacity >= ratio
fun hasCargoRatio(ship: Ship, ratio: Double): Boolean = hasCargoRatio(ship.cargo, ratio)

fun cargoFull(cargo: Cargo): Boolean = cargo.units >= cargo.capacity
fun cargoFull(ship: Ship): Boolean = cargoFull(ship.cargo)

fun cargoNotFull(cargo: Cargo): Boolean = !cargoFull(cargo)
fun cargoNotFull(ship: Ship): Boolean = cargoNotFull(ship.cargo)

fun hasCargo(ship: Ship): Boolean = hasCargo(ship.cargo)
fun hasCargo(cargo: Cargo): Boolean = cargo.units > 0

fun cargoEmpty(ship: Ship): Boolean = cargoEmpty(ship.cargo)
fun cargoEmpty(cargo: Cargo): Boolean = cargo.units == 0

fun findInventoryOfSizeMax(ship: Ship, size: Int): List<Inventory> = findInventoryOfSizeMax(ship.cargo, size)
fun findInventoryOfSizeMax(cargo: Cargo, size: Int): List<Inventory> = findInventoryOfSizeMax(cargo.inventory, size)
fun findInventoryOfSizeMax(inventory: List<Inventory>, size: Int): List<Inventory> = inventory.filter { it.units <= size }

fun cargoSpaceLeft(ship: Ship): Int = cargoSpaceLeft(ship.cargo)
fun cargoSpaceLeft(cargo: Cargo): Int = cargo.capacity - cargo.units

fun transferCargo(
    toShip: Ship, fromShip: Ship,
    good: TradeSymbol, amount: Int,
    cb: KSuspendFunction1<Cargo, Unit>,
    fb: KSuspendFunction2<HttpResponse?, Exception?, Unit>
) {
    SpaceTradersClient.enqueueRequest<Cargo>(
        cb,
        fb,
        request {
            url(api("my/ships/${toShip.symbol}/transfer"))
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(CargoTransferRequest(good, amount, fromShip.symbol))
        }
    )
}

fun sellCargo(
    ship: Ship, symbol: TradeSymbol, units: Int
) {
    SpaceTradersClient.enqueueRequest<SellCargoResponse>(
        ::ignoredCallback,
        ::ignoredFailback,
        request {
            url(api("my/ships/${ship.symbol}/sell"))
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(SellCargoRequest(symbol, units))
        }
    )
}
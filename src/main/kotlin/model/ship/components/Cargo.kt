package model.ship.components

import client.SpaceTradersClient
import client.SpaceTradersClient.ignoredCallback
import client.SpaceTradersClient.ignoredFailback
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import model.ship.Ship
import kotlinx.serialization.Serializable
import model.market.TradeSymbol
import model.api
import requestbody.CargoTransferRequest
import requestbody.SellCargoRequest
import responsebody.SellCargoResponse
import responsebody.TransferResponse
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


fun cargoFull(cargo: Cargo): Boolean = cargo.units >= cargo.capacity
fun cargoNotFull(cargo: Cargo): Boolean = !cargoFull(cargo)
fun hasCargo(cargo: Cargo): Boolean = cargo.units > 0
fun cargoEmpty(cargo: Cargo): Boolean = cargo.units == 0
fun findInventoryOfSizeMax(cargo: Cargo, size: Int): List<Inventory> = findInventoryOfSizeMax(cargo.inventory, size)
fun findInventoryOfSizeMax(inventory: List<Inventory>, size: Int): List<Inventory> = inventory.filter { it.units <= size }
fun cargoSpaceLeft(cargo: Cargo): Int = cargo.capacity - cargo.units
fun removeLocalCargo(cargo: Cargo, inventory: Inventory): Inventory {
    if (cargo.inventory.contains(inventory)) {
        cargo.units -= inventory.units
        cargo.inventory.remove(inventory)
    }
    else {
        println("ERROR: Can't remove inventory we don't own????")
    }
    return inventory
}
fun transferCargo(
    toShip: Ship, fromShip: Ship,
    good: TradeSymbol, amount: Int,
    cb: KSuspendFunction1<TransferResponse, Unit>,
    fb: KSuspendFunction2<HttpResponse?, Exception?, Unit>
) {
    SpaceTradersClient.enqueueRequest<TransferResponse>(
        cb,
        fb,
        request {
            url(api("my/ships/${fromShip.symbol}/transfer"))
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(CargoTransferRequest(good, amount, toShip.symbol))
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
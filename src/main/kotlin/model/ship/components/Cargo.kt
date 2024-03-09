package model.ship.components

import client.SpaceTradersClient
import client.SpaceTradersClient.ignoredFailback
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import model.GameState
import model.api
import model.applyAgentUpdate
import model.market.TradeSymbol
import model.market.applyMarketTransactionUpdate
import model.requestbody.CargoTransferRequest
import model.requestbody.SellCargoRequest
import model.responsebody.BuySellCargoResponse
import model.responsebody.TransferResponse
import model.ship.Ship
import model.ship.shipById
import notification.NotificationManager
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

@Serializable
data class Cargo(
    val capacity: Int,
    var units: Int,
    var inventory: MutableList<Inventory>
)

fun hasCargoRatio(cargo: Cargo, ratio: Double): Boolean =
    if(cargo.capacity == 0) false
    else cargo.units / cargo.capacity >= ratio


/**
 * It's not possible to exceed capacity, but this equality check is here
 * to prevent odd fail cases.
 */
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

fun removeLocalCargo(cargo: Cargo, symbol: TradeSymbol): Inventory =
    removeLocalCargo(cargo, cargo.inventory.first { i -> i.symbol == symbol })
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

/**
 * Returns max of the trade volume that will be allowed in a single sale/buy order.
 *
 * Zero will be returned if: the market doesn't exist, its list of trade goods isn't yet mapped, or
 * the trade good isn't available for sale.
 */
fun getMarketVolumeForGood(symbol: TradeSymbol, marketSymbol: String): Int {
    return GameState.markets[marketSymbol]?.tradeGoods?.firstOrNull { t -> t.symbol == symbol }?.tradeVolume ?: 0
}

fun sellCargo(
    ship: Ship, symbol: TradeSymbol, units: Int, successCb: KSuspendFunction1<BuySellCargoResponse, Unit>
) {
    if (getMarketVolumeForGood(symbol, ship.nav.waypointSymbol) < units) {
        println("Too many units requested for sale at market.")
        NotificationManager.errorNotification(
            "Can't sell $units $symbol at ${ship.nav.waypointSymbol}", "Bad"
        )
        return
    }
    val tradeGoodInv = ship.cargo.inventory.firstOrNull { i -> i.symbol == symbol }
    if (tradeGoodInv == null) {
        NotificationManager.errorNotification(
            "Tried to sell a good we don't have???", "$ship, $symbol, $units"
        )
    } else {
        SpaceTradersClient.enqueueRequest<BuySellCargoResponse>(
            successCb,
            ::ignoredFailback,
            request {
                url(api("my/ships/${ship.symbol}/sell"))
                method = HttpMethod.Post
                contentType(ContentType.Application.Json)
                setBody(SellCargoRequest(symbol, units))
            }
        )
    }
}

fun applyCargoUpdate(cargo: Cargo, ship: Ship) {
    val sCargo = ship.cargo
    sCargo.units = cargo.units
    sCargo.inventory = cargo.inventory
}

suspend fun buySellCargoCbAndApply(buySellCargoResponse: BuySellCargoResponse) {
    val ship = shipById(buySellCargoResponse.transaction.shipSymbol)
    if (ship == null) {
        NotificationManager.errorNotification(
            "Given cargo response for ${buySellCargoResponse.transaction.shipSymbol} but not cached.",
            "Bad"
        )
        return
    }
    applyAgentUpdate(buySellCargoResponse.agent)
    applyCargoUpdate(buySellCargoResponse.cargo, ship)
    applyMarketTransactionUpdate(buySellCargoResponse.transaction)
}

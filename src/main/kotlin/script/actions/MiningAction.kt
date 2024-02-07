package script.actions

import client.SpaceTradersClient
import io.ktor.client.request.*
import model.actions.Extract
import model.api
import model.ship.Ship
import script.StateScope

fun StateScope.mine(ship: Ship, mineCallback:(Extract) -> Unit) {
    SpaceTradersClient.asyncGet<Extract>(mineCallback, request {
        url(api("my/ships/${ship.symbol}/extract"))
    })
}
package script.actions

import client.SpaceTradersClient
import io.ktor.client.request.*
import model.actions.Extract
import model.api
import model.ship.Ship
import script.StateScope

fun StateScope.mine(ship: Ship, mineCallback:(Extract) -> Unit) {
    SpaceTradersClient.enqueueRequest<Extract>(
        callback = mineCallback,
        failback = { resp, ex ->
            if (resp != null) {
                println("Got bad response from request")
            }
            else if(ex != null) {
                println("Exception")
            }
            else {
                println("Totally empty????")
            }
        },
        request {
            url(api("my/ships/${ship.symbol}/extract"))
        }
    )
}
package script.actions

import client.SpaceTradersClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import model.actions.Extract
import model.api
import model.ship.Ship
import script.StateScope
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

fun StateScope.mine(
    ship: Ship,
    mineCallback: KSuspendFunction1<Extract, Unit>,
    failback: KSuspendFunction2<HttpResponse?, Exception?, Unit>
) {
    SpaceTradersClient.enqueueRequest<Extract>(
        mineCallback,
        failback,
        request {
            url(api("my/ships/${ship.symbol}/extract"))
            method = HttpMethod.Post
        }
    )
}
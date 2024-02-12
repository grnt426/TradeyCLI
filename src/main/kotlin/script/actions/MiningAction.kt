package script.actions

import client.SpaceTradersClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import responsebody.ExtractionResponse
import model.api
import model.ship.Ship
import script.StateScope
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

fun StateScope.mine(
    ship: Ship,
    mineCallback: KSuspendFunction1<ExtractionResponse, Unit>,
    failback: KSuspendFunction2<HttpResponse?, Exception?, Unit>
) {
    SpaceTradersClient.enqueueRequest<ExtractionResponse>(
        mineCallback,
        failback,
        request {
            url(api("my/ships/${ship.symbol}/extract"))
            method = HttpMethod.Post
        }
    )
}
package model.ship

import client.SpaceTradersClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import model.api
import model.responsebody.NavigationResponse
import model.system.WaypointSymbol
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

@Serializable
data class Navigation(
    val systemSymbol: String,
    val waypointSymbol: String,
    val route: Route,
    val status: ShipNavStatus,
    val flightMode: String
)

fun navigateTo(
    ship: Ship,
    waypointSymbol: String,
    cb: KSuspendFunction1<NavigationResponse, Unit> = SpaceTradersClient::ignoredCallback,
    fb: KSuspendFunction2<HttpResponse?, Exception?, Unit> = SpaceTradersClient::ignoredFailback
) {
    SpaceTradersClient.enqueueRequest<NavigationResponse>(
        cb,
        fb,
        request {
            url(api("my/ships/${ship.symbol}/navigate"))
            method = HttpMethod.Post
            contentType(ContentType.Application.Json)
            setBody(WaypointSymbol(waypointSymbol))
        }
    )
}

fun isNavigating(navigation: Navigation): Boolean = navigation.status == ShipNavStatus.IN_TRANSIT
fun isOrbiting(navigation: Navigation): Boolean = navigation.status == ShipNavStatus.IN_ORBIT
fun isDocked(navigation: Navigation): Boolean = navigation.status == ShipNavStatus.DOCKED
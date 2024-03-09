package model.ship

import client.SpaceTradersClient
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import model.api
import model.responsebody.NavigationResponse
import model.system.WaypointSymbol
import notification.NotificationManager
import java.time.Instant
import kotlin.reflect.KSuspendFunction1
import kotlin.reflect.KSuspendFunction2

@Serializable
data class Navigation(
    val systemSymbol: String,
    var waypointSymbol: String,
    val route: Route,
    var status: ShipNavStatus,
    val flightMode: String
)

class NavigationCallbackHandler(val ship: Ship, val waypointSymbol: String, var attempts: Int = 0) {

    suspend fun success(resp: NavigationResponse) {
        ship.nav = resp.nav
        if (resp.fuel != null) ship.fuel = resp.fuel
    }

    suspend fun failback(resp: HttpResponse?, ex: Exception?) {
        attempts++
        NotificationManager.errorNotification("Failed to navigate ${ship.registration.name}")

        // try again
        if (attempts <= 3) navigateTo(ship, waypointSymbol, ::success, ::failback)
        else NotificationManager.errorNotification(
            "Too many attempts to navigate ${ship.registration.name}"
        )
    }
}

fun navigateTo(
    ship: Ship,
    waypointSymbol: String
) {
    val handler = NavigationCallbackHandler(ship, waypointSymbol)
    navigateTo(ship, waypointSymbol, handler::success, handler::failback)
}

fun navigateTo(
    ship: Ship,
    waypointSymbol: String,
    cb: KSuspendFunction1<NavigationResponse, Unit>,
    fb: KSuspendFunction2<HttpResponse?, Exception?, Unit>
) {
    if (isDocked(ship)) {
        toOrbit(ship)
    }
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

/**
 * A ship is navigating if its status flag is set to that, and if its
 * arrival is still in the future, plus a small epsilon to account for
 * clock drift.
 */
fun isNavigating(navigation: Navigation): Boolean {
    return navigation.status == ShipNavStatus.IN_TRANSIT &&
            navigation.route.arrival > Instant.now()
}
fun isOrbiting(navigation: Navigation): Boolean = navigation.status == ShipNavStatus.IN_ORBIT
fun isDocked(navigation: Navigation): Boolean = navigation.status == ShipNavStatus.DOCKED
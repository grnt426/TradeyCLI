package notification

import engine.Event
import io.github.oshai.kotlinlogging.KotlinLogging
import screen.ColorPalette
import screen.TextAnimationContainer
import java.time.Instant

private val logger = KotlinLogging.logger {}
object NotificationManager {
    private const val MAX_NOTIFICATIONS = 5
    val notifications = mutableListOf<Notification>()

    fun exceptNotification(short: String, long: String, e: Exception) {
        logger.error(e) {
            "$short - $long"
        }
        addNotification(
            Notification(
                short, Instant.now(),
                TextAnimationContainer.exceptNotification!!, ColorPalette.errorRed,
                long
            )
        )
    }

    fun errorNotification(short: String, long: String) {
        logger.error {
            "$short${if (long.isNotEmpty()) " - $long" else ""}"
        }
        addNotification(
            Notification(
                short, Instant.now(),
                TextAnimationContainer.errorNotification!!, ColorPalette.errorRed,
                long
            )
        )
    }

    fun errorNotification(short: String) {
        errorNotification(short, "")
    }

    fun createNotification(short: String, long: String) {
        addNotification(
            Notification(
                short, Instant.now(),
                TextAnimationContainer.positiveNotification!!, ColorPalette.informationGreen,
                long
            )
        )
    }

    /** Engine events the dashboard should show. Quiet ones are left to the log. */
    fun onEvent(event: Event) {
        when (event) {
            is Event.Booted -> createNotification("Welcome, ${event.agent}", "Server reset ${event.resetDate}")
            is Event.SystemLoaded -> createNotification(
                "${event.system} loaded", "${event.waypoints} waypoints, ${event.markets} markets, ${event.shipyards} shipyards"
            )
            is Event.Warning -> errorNotification(event.message)
            is Event.Failure -> if (event.cause is Exception) exceptNotification(event.message, "", event.cause) else errorNotification(event.message)
            is Event.BehaviourFailed -> errorNotification("${event.ship} ${event.behaviour} failed", event.reason)
            is Event.BehaviourFinished -> createNotification("${event.ship} ${event.behaviour} finished", "")
            is Event.ShipPurchased -> createNotification("Bought ${event.ship}", "${event.type} for ${event.credits}")
            is Event.ContractFulfilled -> createNotification("Contract fulfilled", "+${event.credits}")
            is Event.Charted -> createNotification("Charted ${event.waypoint}", "+${event.credits}")
            is Event.Supplied -> createNotification("Supplied ${event.site}", "${event.units} ${event.good}, ${event.remaining} to go")
            is Event.ShipsLoaded, is Event.MarketUpdated, is Event.PhaseChanged, is Event.BehaviourStarted,
            is Event.Extracted, is Event.Sold, is Event.Bought, is Event.Refueled, is Event.Surveyed,
            is Event.ContractOffered, is Event.Delivered -> Unit
        }
    }

    private fun addNotification(notif: Notification) {
        if (notifications.size == MAX_NOTIFICATIONS)
            notifications.removeFirst()
        notifications.add(notif)
    }
}
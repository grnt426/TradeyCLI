package notification

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

    private fun addNotification(notif: Notification) {
        if (notifications.size == MAX_NOTIFICATIONS)
            notifications.removeFirst()
        notifications.add(notif)
    }
}
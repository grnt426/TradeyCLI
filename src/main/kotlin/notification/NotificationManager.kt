package notification

import screen.ColorPalette
import screen.TextAnimationContainer
import java.time.Instant

object NotificationManager {
    private const val MAX_NOTIFICATIONS = 5
    val notifications = mutableListOf<Notification>()

    fun createErrorNotification(short: String, long: String) {
        addNotification(
            Notification(
                short, Instant.now(),
                TextAnimationContainer.errorNotification!!, ColorPalette.errorRed,
                long
            )
        )
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
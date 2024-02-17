package model.ship

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import notification.NotificationManager
import java.time.Instant

@Serializable
data class Cooldown(
    val shipSymbol: String,
    val totalSeconds: Long,
    var remainingSeconds: Long,
    val expiration: String? = null,
    @Transient var expirationDateTime: Instant? = null,
)

fun isCooldownExpired(cooldown: Cooldown): Boolean {
    return if (cooldown.expiration == null && cooldown.remainingSeconds > 0) {
        false
    }
    else if (cooldown.expiration != null) {
        if (cooldown.expirationDateTime == null) {
            cooldown.expirationDateTime = Instant.parse(cooldown.expiration)
        }
        Instant.now().toEpochMilli() >= cooldown.expirationDateTime!!.toEpochMilli()
    }
    else {
        if (cooldown.remainingSeconds < -120) {
            NotificationManager.createErrorNotification("Negative cooldown of over 2 mins", "Why")
        }
        return cooldown.remainingSeconds  <= 0
    }
}

fun hasCooldown(ship: Ship): Boolean = !isCooldownExpired(ship.cooldown)

fun calculateExpirationSeconds(ship: Ship): Long {
    return ship.nav.route.arrival.epochSecond.minus(Instant.now().epochSecond)
}
package model.ship

import kotlinx.serialization.Serializable
import model.extension.InstantSerializer
import java.time.Instant

@Serializable
data class Cooldown(
    val shipSymbol: String,
    val totalSeconds: Long,
    val remainingSeconds: Long,
    @Serializable(with = InstantSerializer::class) val expiration: Instant? = null,
) {
    /** When the cooldown ends, or null if there is none. Prefers the absolute time the API gives. */
    fun expiresAt(now: Instant): Instant? = when {
        expiration != null -> expiration.takeIf { it.isAfter(now) }
        remainingSeconds > 0 -> now.plusSeconds(remainingSeconds)
        else -> null
    }

    fun isActiveAt(now: Instant): Boolean = expiresAt(now) != null
}

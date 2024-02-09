package model.ship

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant

@Serializable
data class Cooldown(
    val shipSymbol: String,
    val totalSeconds: Long,
    val remainingSeconds: Long,
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
        true
    }
}
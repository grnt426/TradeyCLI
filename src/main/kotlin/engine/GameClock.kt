package engine

import kotlinx.coroutines.delay
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where "now" comes from and how waiting happens. Behaviours and verbs never call
 * `Instant.now()` or `delay` directly, so the same code runs against the live API on wall-clock
 * time and against the simulator on virtual or accelerated time.
 */
interface GameClock {
    fun now(): Instant
    suspend fun sleep(duration: Duration)

    suspend fun sleepUntil(instant: Instant) {
        val remaining = instant.toEpochMilli() - now().toEpochMilli()
        if (remaining > 0) sleep(remaining.milliseconds)
    }
}

/** Wall-clock time and real waiting; the live client's clock. */
object SystemClock : GameClock {
    override fun now(): Instant = Instant.now()
    override suspend fun sleep(duration: Duration) = delay(duration)
}

/**
 * Wall-clock time sped up by [factor]: a minute of game time passes in a second at 60. Used to
 * watch a behaviour run against the simulator in the dashboard without waiting for real travel.
 */
class AcceleratedClock(private val factor: Double, private val base: Instant = Instant.now()) : GameClock {
    private val started = System.nanoTime()

    override fun now(): Instant {
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        return base.plusMillis((elapsedMs * factor).toLong())
    }

    override suspend fun sleep(duration: Duration) = delay((duration.inWholeMilliseconds / factor).toLong().coerceAtLeast(1))
}

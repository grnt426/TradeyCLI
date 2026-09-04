package sim

import engine.GameClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.time.Instant
import kotlin.time.Duration

/**
 * Time that only moves when every coroutine is waiting. Backed by a test scheduler, so a day of
 * mining runs in milliseconds; `now()` is the scheduler's clock offset from [base].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirtualClock(val scheduler: TestCoroutineScheduler, private val base: Instant) : GameClock {
    override fun now(): Instant = base.plusMillis(scheduler.currentTime)
    override suspend fun sleep(duration: Duration) = delay(duration)
}

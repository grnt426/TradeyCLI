package api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RequestPacerTest {

    private val smallLimits = RateLimits(staticPoints = 2, staticWindow = 1.seconds, burstPoints = 3, burstWindow = 10.seconds)

    @Test
    fun `static pool first, then burst, then wait for the static refill`() = runTest {
        val pacer = RequestPacer(backgroundScope, smallLimits, testScheduler.timeSource)
        val granted = mutableListOf<Int>()
        repeat(7) { i -> launch { pacer.acquire(Priority.BACKGROUND); granted += i } }

        runCurrent()
        assertEquals(listOf(0, 1, 2, 3, 4), granted, "two static plus three burst slots are immediate")
        assertEquals(2, pacer.queued)

        advanceTimeBy(999)
        runCurrent()
        assertEquals(5, granted.size, "nothing refills before the static window ends")

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), granted, "static pool refills to two one second after its first consume")
        assertEquals(0, pacer.queued)
    }

    @Test
    fun `interactive requests jump ahead of background ones`() = runTest {
        val limits = RateLimits(staticPoints = 1, staticWindow = 1.seconds, burstPoints = 1, burstWindow = 60.seconds)
        val pacer = RequestPacer(backgroundScope, limits, testScheduler.timeSource)
        val order = mutableListOf<String>()

        launch { pacer.acquire(Priority.BACKGROUND); order += "a" }
        launch { pacer.acquire(Priority.BACKGROUND); order += "b" }
        runCurrent()
        assertEquals(listOf("a", "b"), order)

        launch { pacer.acquire(Priority.BACKGROUND); order += "bg" }
        launch { pacer.acquire(Priority.INTERACTIVE); order += "ui" }
        runCurrent()
        assertEquals(listOf("a", "b"), order, "both pools are empty")

        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf("a", "b", "ui"), order, "the refilled static slot goes to the interactive request")

        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf("a", "b", "ui", "bg"), order)
    }

    @Test
    fun `refill happens one window after the first consume, not the last`() = runTest {
        val limits = RateLimits(staticPoints = 2, staticWindow = 1.seconds, burstPoints = 1, burstWindow = 60.seconds)
        val pacer = RequestPacer(backgroundScope, limits, testScheduler.timeSource)
        var done = 0

        launch { pacer.acquire(Priority.ACTION); done++ }          // t=0, static window starts
        runCurrent()
        advanceTimeBy(500)
        launch { pacer.acquire(Priority.ACTION); done++ }          // t=0.5, static
        launch { pacer.acquire(Priority.ACTION); done++ }          // t=0.5, burst
        launch { pacer.acquire(Priority.ACTION); done++ }          // waits
        runCurrent()
        assertEquals(3, done)

        advanceTimeBy(500)                                          // t=1.0: static refills
        runCurrent()
        assertEquals(4, done)
    }

    @Test
    fun `a cancelled waiter leaves the queue`() = runTest {
        val limits = RateLimits(staticPoints = 1, staticWindow = 1.seconds, burstPoints = 1, burstWindow = 60.seconds)
        val pacer = RequestPacer(backgroundScope, limits, testScheduler.timeSource)
        launch { pacer.acquire(Priority.ACTION) }
        launch { pacer.acquire(Priority.ACTION) }
        runCurrent()
        val waiting = launch { pacer.acquire(Priority.ACTION) }
        runCurrent()
        assertEquals(1, pacer.queued)
        waiting.cancel()
        runCurrent()
        assertEquals(0, pacer.queued)
    }
}

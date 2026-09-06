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
    fun `idle requests only take a static point nobody else wanted, after a quiet moment`() = runTest {
        val pacer = RequestPacer(backgroundScope, smallLimits, testScheduler.timeSource)
        val order = mutableListOf<String>()
        launch { pacer.acquire(Priority.IDLE); order += "idle1" }
        launch { pacer.acquire(Priority.IDLE); order += "idle2" }
        runCurrent()
        assertEquals(listOf("idle1"), order, "a full static pool gives idle work one point at once when nothing real has run")
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("idle1"), order, "the second point is kept for real work; idle waits for the refill")

        launch { pacer.acquire(Priority.BACKGROUND); order += "bg" }
        runCurrent()
        assertEquals(listOf("idle1", "bg"), order, "real work takes the point idle left behind, at once")

        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf("idle1", "bg", "idle2"), order, "after the refill and a quiet moment, idle gets its point")

        launch { pacer.acquire(Priority.BACKGROUND); order += "bg2" }
        launch { pacer.acquire(Priority.IDLE); order += "idle3" }
        runCurrent()
        assertEquals(listOf("idle1", "bg", "idle2", "bg2"), order, "real work first; idle must wait for quiet")
        advanceTimeBy(299)
        runCurrent()
        assertEquals(4, order.size, "still inside the quiet gap")
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf("idle1", "bg", "idle2", "bg2", "idle3"), order)
        assertEquals(3, pacer.idleGranted)
    }

    @Test
    fun `a throttle from the server stands idle work down for a while`() = runTest {
        val pacer = RequestPacer(backgroundScope, smallLimits, testScheduler.timeSource)
        val order = mutableListOf<String>()
        pacer.noteThrottled()
        launch { pacer.acquire(Priority.IDLE); order += "idle" }
        launch { pacer.acquire(Priority.BACKGROUND); order += "bg" }
        runCurrent()
        assertEquals(listOf("bg"), order, "real work is unaffected; idle waits out the backoff")
        advanceTimeBy(29_000)
        runCurrent()
        assertEquals(listOf("bg"), order)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(listOf("bg", "idle"), order)
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

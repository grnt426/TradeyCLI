package api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Order in which queued requests get the next slot. Earlier entries win. */
enum class Priority { INTERACTIVE, ACTION, BACKGROUND }

/** Coarse view of how backed up the pacer is, for the dashboard. */
enum class JobPressure { LOW, OK, HIGH }

fun pressureOf(queued: Int): JobPressure = when {
    queued <= 7 -> JobPressure.LOW
    queued <= 15 -> JobPressure.OK
    else -> JobPressure.HIGH
}

/**
 * The server's two pools, as documented in api-docs/wiki/Ratelimit.md. Each pool starts a timer on
 * its first consume and refills to full once its window has passed, regardless of later consumes.
 * The static pool is used first; the burst pool absorbs what it cannot.
 */
data class RateLimits(
    val staticPoints: Int,
    val staticWindow: Duration,
    val burstPoints: Int,
    val burstWindow: Duration,
) {
    init {
        require(staticPoints > 0 && burstPoints > 0) { "Both pools need at least one point" }
    }

    companion object {
        /** Enforced per account, so every agent of the account shares one pacer. */
        val SPACE_TRADERS = RateLimits(2, 1.seconds, 30, 60.seconds)
    }
}

/**
 * Hands out permission to start a request at a rate the server will accept, highest priority
 * first. Requests may run concurrently; only their starts are paced.
 *
 * All state is guarded by one mutex and served by a single scheduler coroutine on [scope].
 */
class RequestPacer(
    scope: CoroutineScope,
    private val limits: RateLimits = RateLimits.SPACE_TRADERS,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    sampleEvery: Duration = 1500.milliseconds,
) {
    private inner class Pool(private val capacity: Int, private val window: Duration) {
        private var points = capacity
        private var windowStart: TimeMark? = null

        fun tryConsume(): Boolean {
            val start = windowStart
            if (start != null && start.elapsedNow() >= window) {
                points = capacity
                windowStart = null
            }
            if (points == 0) return false
            if (windowStart == null) windowStart = timeSource.markNow()
            points--
            return true
        }

        /** Time until this pool refills. Only meaningful once it has been consumed from. */
        fun untilRefill(): Duration =
            windowStart?.let { (window - it.elapsedNow()).coerceAtLeast(Duration.ZERO) } ?: Duration.ZERO
    }

    private val staticPool = Pool(limits.staticPoints, limits.staticWindow)
    private val burstPool = Pool(limits.burstPoints, limits.burstWindow)
    private val queues = Priority.entries.map { ArrayDeque<CompletableDeferred<Unit>>() }
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val history = IntArray(HISTORY_LENGTH)

    /** Requests waiting for a slot right now. */
    @Volatile
    var queued: Int = 0
        private set

    /** Slots handed out since start. */
    @Volatile
    var granted: Long = 0
        private set

    /** Queue depth sampled every [sampleEvery], oldest first. */
    val queueHistory: IntArray
        get() = synchronized(history) { history.copyOf() }

    init {
        scope.launch { schedule() }
        scope.launch {
            while (true) {
                delay(sampleEvery)
                synchronized(history) {
                    history.copyInto(history, 0, 1)
                    history[history.lastIndex] = queued
                }
            }
        }
    }

    /** Suspends until a request of this [priority] may start. */
    suspend fun acquire(priority: Priority) {
        val permit = CompletableDeferred<Unit>()
        val queue = queues[priority.ordinal]
        lock.withLock {
            queue.addLast(permit)
            queued++
        }
        wake.trySend(Unit)
        try {
            permit.await()
        } catch (e: CancellationException) {
            lock.withLock {
                if (queue.remove(permit)) queued--
            }
            throw e
        }
    }

    suspend fun <T> withSlot(priority: Priority, block: suspend () -> T): T {
        acquire(priority)
        return block()
    }

    private suspend fun schedule() {
        while (true) {
            var grant: CompletableDeferred<Unit>? = null
            var wait: Duration? = null
            lock.withLock {
                val queue = queues.firstOrNull { it.isNotEmpty() }
                if (queue != null) {
                    if (staticPool.tryConsume() || burstPool.tryConsume()) {
                        grant = queue.removeFirst()
                        queued--
                        granted++
                    } else {
                        wait = minOf(staticPool.untilRefill(), burstPool.untilRefill()).coerceAtLeast(1.milliseconds)
                    }
                }
            }
            val g = grant
            val w = wait
            when {
                g != null -> g.complete(Unit)
                w != null -> withTimeoutOrNull(w) { wake.receive() }
                else -> wake.receive()
            }
        }
    }

    companion object {
        const val HISTORY_LENGTH = 20
    }
}

package plan

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Duration
import java.time.Instant

/** Who is driving the plan: written by `run` next to the plan file, refreshed while it runs. */
@Serializable
data class RunLease(
    val pid: Long,
    val startedAt: String,
    val heartbeatAt: String,
) {
    val started: Instant get() = Instant.parse(startedAt)
    val heartbeat: Instant get() = Instant.parse(heartbeatAt)

    /** Whether the process still exists here and has written a heartbeat recently. */
    fun isLive(now: Instant, processAlive: (Long) -> Boolean = RunLock::processAlive): Boolean =
        processAlive(pid) && Duration.between(heartbeat, now) < RunLock.STALE_AFTER
}

/**
 * One driver per agent. Two supervisors on the same ships would issue conflicting actions and
 * burn the shared request budget on the failures, so `run` takes this lease first and refuses to
 * start while another live process holds it. A lease whose process is gone, or whose heartbeat
 * is older than [STALE_AFTER], counts as abandoned and is taken over. The dashboard reads the
 * lease to say whether anyone is driving.
 */
object RunLock {
    val STALE_AFTER: Duration = Duration.ofSeconds(90)
    val HEARTBEAT_EVERY: Duration = Duration.ofSeconds(15)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    sealed class Outcome {
        data class Held(val lease: RunLease) : Outcome()
        data class Busy(val lease: RunLease) : Outcome()
    }

    fun read(file: File): RunLease? = runCatching { json.decodeFromString<RunLease>(file.readText()) }.getOrNull()

    /** Takes the lease unless a live process holds it. */
    fun acquire(file: File, pid: Long = currentPid(), now: Instant = Instant.now(), processAlive: (Long) -> Boolean = ::processAlive): Outcome {
        val existing = read(file)
        if (existing != null && existing.pid != pid && existing.isLive(now, processAlive)) return Outcome.Busy(existing)
        val lease = RunLease(pid, now.toString(), now.toString())
        write(file, lease)
        return Outcome.Held(lease)
    }

    /** Refreshes the heartbeat; returns the lease, or null if somebody else took the file over. */
    fun heartbeat(file: File, lease: RunLease, now: Instant = Instant.now()): RunLease? {
        val current = read(file)
        if (current != null && current.pid != lease.pid) return null
        val next = lease.copy(heartbeatAt = now.toString())
        write(file, next)
        return next
    }

    /** Removes the lease if this process holds it. */
    fun release(file: File, pid: Long = currentPid()) {
        if (read(file)?.pid == pid) file.delete()
    }

    fun currentPid(): Long = ProcessHandle.current().pid()

    fun processAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    private fun write(file: File, lease: RunLease) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(lease))
    }
}

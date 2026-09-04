package plan

import behaviour.decisions.Intent
import behaviour.decisions.Intentions
import engine.Snapshot
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunLockTest {
    private val now = Instant.parse("2026-09-04T12:00:00Z")
    private fun file() = Files.createTempDirectory("tradey-lock").resolve("run.lock").toFile()

    @Test
    fun `a live holder blocks a second run, a dead or stale one is taken over, and release removes the file`() {
        val f = file()
        val alive = { pid: Long -> pid == 1L || pid == 2L }
        assertTrue(RunLock.acquire(f, pid = 1, now = now, processAlive = alive) is RunLock.Outcome.Held)
        val busy = RunLock.acquire(f, pid = 2, now = now.plusSeconds(5), processAlive = alive)
        assertTrue(busy is RunLock.Outcome.Busy && busy.lease.pid == 1L, busy.toString())
        // the holder dies: taken over
        val dead = { pid: Long -> pid == 2L }
        assertTrue(RunLock.acquire(f, pid = 2, now = now.plusSeconds(10), processAlive = dead) is RunLock.Outcome.Held)
        assertEquals(2L, RunLock.read(f)!!.pid)
        // the holder lives but stopped heart-beating: taken over after the stale window
        assertTrue(RunLock.acquire(f, pid = 3, now = now.plusSeconds(10) + RunLock.STALE_AFTER.plusSeconds(1), processAlive = { true }) is RunLock.Outcome.Held)
        // the old holder's heartbeat notices it lost the file
        assertNull(RunLock.heartbeat(f, RunLease(2, now.toString(), now.toString()), now))
        RunLock.release(f, pid = 2)
        assertTrue(f.exists(), "only the holder may release")
        RunLock.release(f, pid = 3)
        assertTrue(!f.exists())
    }

    @Test
    fun `the intentions panel says who is driving`() {
        val empty = Snapshot(1, null, null, null, null, emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())
        Intentions.processAlive = { pid -> pid == 42L }
        try {
            assertTrue(Intentions.describe(empty, now).first().text.startsWith("Nobody is running"))
            val live = empty.copy(runner = RunLease(42, now.minusSeconds(600).toString(), now.minusSeconds(10).toString()))
            val driven = Intentions.describe(live, now).first()
            assertEquals(Intent.Tone.GOOD, driven.tone)
            assertTrue(driven.text.startsWith("Plan driven by process 42") && driven.text.endsWith("heartbeat 10s ago"), driven.text)
            val gone = empty.copy(runner = RunLease(7, now.minusSeconds(600).toString(), now.minus(Duration.ofMinutes(20)).toString()))
            val warning = Intentions.describe(gone, now).first()
            assertEquals(Intent.Tone.WARN, warning.tone)
            assertTrue(warning.text.contains("process 7 is gone") && warning.text.contains("20 min ago"), warning.text)
        } finally {
            Intentions.processAlive = RunLock::processAlive
        }
    }
}

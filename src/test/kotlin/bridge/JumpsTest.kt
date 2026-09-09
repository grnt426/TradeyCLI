package bridge

import bridge.views.GalaxyMap
import bridge.views.GalaxyMap.Gate
import storage.ActivityRecord
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class JumpsTest {
    private val now: Instant = Instant.parse("2026-09-07T12:00:00Z")
    private fun jump(ship: String, detail: String, ago: Duration) = ActivityRecord(now.minus(ago), ship, "pioneer", "jump", detail, 60)

    @Test
    fun `jumps inside the window become hops between systems, newest first`() {
        val hops = RecentJumps.recent(
            listOf(
                jump("A-1", "X1-AA11-I55 -> X1-BB22-I56", Duration.ofMinutes(4)),
                jump("A-2", "X1-BB22-I56 -> X1-CC33-I57", Duration.ofMinutes(1)),
                jump("A-3", "X1-AA11-I55 -> X1-BB22-I56", Duration.ofMinutes(6)), // outside the window
                ActivityRecord(now.minusSeconds(30), "A-4", "trade", "cruise", "X1-AA11-A1 -> X1-AA11-B2 (40)", 90), // not a jump
                jump("A-5", "X1-DD44-I58", Duration.ofMinutes(2)), // the old format: destination only
            ),
            now,
        )
        assertEquals(listOf("A-2", "A-1"), hops.map { it.ship })
        assertEquals("X1-BB22" to "X1-CC33", hops[0].from to hops[0].to)
        assertEquals(0.2, hops[0].age(now), 1e-9)
        assertEquals(0.8, hops[1].age(now), 1e-9)
    }

    @Test
    fun `hops group by the link in the direction flown, busiest first`() {
        val hops = RecentJumps.recent(
            listOf(
                jump("A-1", "X1-AA11-I55 -> X1-BB22-I56", Duration.ofMinutes(1)),
                jump("A-2", "X1-AA11-I55 -> X1-BB22-I56", Duration.ofMinutes(2)),
                jump("A-3", "X1-BB22-I56 -> X1-AA11-I55", Duration.ofMinutes(3)),
            ),
            now,
        )
        val links = RecentJumps.byLink(hops)
        assertEquals(listOf("X1-AA11" to "X1-BB22", "X1-BB22" to "X1-AA11"), links.map { it.first })
        assertEquals(listOf(2, 1), links.map { it.second.size })
    }

    @Test
    fun `a gate's standing takes the surest source first`() {
        // A ship saw it finished, or the plan entered the system: open, whatever an older read said.
        assertEquals(Gate.OPEN, GalaxyMap.gateStanding(seen = false, planBuilt = false, read = true) { true })
        assertEquals(Gate.OPEN, GalaxyMap.gateStanding(seen = null, planBuilt = true, read = true) { true })
        // Otherwise the console's read decides.
        assertEquals(Gate.UNBUILT, GalaxyMap.gateStanding(seen = null, planBuilt = false, read = true) { true })
        assertEquals(Gate.OPEN, GalaxyMap.gateStanding(seen = null, planBuilt = false, read = false) { true })
        assertEquals(Gate.OPEN, GalaxyMap.gateStanding(seen = true, planBuilt = false, read = false) { true })
        // A ship's sighting of construction, with no read since, still counts.
        assertEquals(Gate.UNBUILT, GalaxyMap.gateStanding(seen = true, planBuilt = false, read = null) { true })
        // Nothing known: unknown with a gate, none without.
        assertEquals(Gate.UNKNOWN, GalaxyMap.gateStanding(seen = null, planBuilt = false, read = null) { true })
        assertEquals(Gate.NONE, GalaxyMap.gateStanding(seen = null, planBuilt = false, read = null) { false })
    }
}

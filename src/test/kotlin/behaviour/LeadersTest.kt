package behaviour

import behaviour.decisions.AgentSample
import behaviour.decisions.Leaders
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class LeadersTest {
    private val t0 = Instant.parse("2026-09-06T21:00:00Z")

    @Test
    fun `an agent's rate is its bank change per hour from first to last sample, per ship on the last count, and a lone sample is left out`() {
        val samples = listOf(
            AgentSample("BIG", t0, 1_000_000, 30), AgentSample("BIG", t0.plusSeconds(3600), 1_120_000, 34),
            AgentSample("SMALL", t0, 1_000_000, 3), AgentSample("SMALL", t0.plusSeconds(1800), 1_200_000, 3), AgentSample("SMALL", t0.plusSeconds(7200), 1_800_000, 3),
            AgentSample("NEW", t0.plusSeconds(7200), 175_000, 2),
        )
        val rates = Leaders.rates(samples)
        assertEquals(listOf("SMALL", "BIG"), rates.map { it.symbol })
        val small = rates.first()
        assertEquals(400_000.0, small.perHour)
        assertEquals(400_000.0 / 3, small.perShipHour)
        val big = rates.last()
        assertEquals(120_000.0, big.perHour)
        assertEquals(4, big.shipsBought)
        assertEquals(34, big.ships)
    }
}

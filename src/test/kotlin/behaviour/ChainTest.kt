package behaviour

import behaviour.decisions.Chains
import model.market.MarketTransaction
import model.market.TradeSymbol
import model.market.TransactionType
import plan.Assignment
import plan.Chain
import plan.Leg
import plan.Plan
import sim.Fixtures
import sim.SimRun
import sim.SimSeed
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChainTest {
    private val now = Instant.parse("2026-09-04T18:00:00Z")
    private val legs = listOf(Leg(TradeSymbol.SILICON_CRYSTALS, "X1-TH77-H52", "X1-TH77-A3"), Leg(TradeSymbol.COPPER, "X1-TH77-H50", "X1-TH77-A3"), Leg(TradeSymbol.MICROPROCESSORS, "X1-TH77-A3", "X1-TH77-D42"))

    private fun chain(hold: Boolean, enrolledHoursAgo: Long, ships: List<String> = listOf("S-1", "S-2"), lastRelease: Instant? = null) =
        Chain("chips", legs, ships, ships.associateWith { 100_000.0 }, now.minus(Duration.ofHours(enrolledHoursAgo)).toString(), hold, lastRelease?.toString())

    private fun sale(minutesAgo: Long, total: Int, ship: String = "S-1") =
        MarketTransaction(ship, "X1-TH77-D42", TradeSymbol.MICROPROCESSORS, TransactionType.SELL, 20, total / 20, total, now.minus(Duration.ofMinutes(minutesAgo)).toString())
    private fun buy(minutesAgo: Long, total: Int, ship: String = "S-1") =
        MarketTransaction(ship, "X1-TH77-A3", TradeSymbol.MICROPROCESSORS, TransactionType.PURCHASE, 20, total / 20, total, now.minus(Duration.ofMinutes(minutesAgo)).toString())

    @Test
    fun `the ledger nets each leg and the policy is sluggish by design`() {
        // Three hours of a chain making 300k an hour, then an hour of losing money.
        val good = (0 until 6).flatMap { i -> listOf(buy(180L - i * 30, 100_000), sale(175L - i * 30, 250_000)) }
        val bad = (0 until 4).flatMap { i -> listOf(buy(50L - i * 12, 100_000), sale(45L - i * 12, 60_000)) }
        val ledger = Chains.ledger(chain(hold = false, enrolledHoursAgo = 4), good + bad, now)
        assertEquals(3, ledger.legs.size)
        val chips = ledger.legs.first { it.good == TradeSymbol.MICROPROCESSORS }
        assertEquals(10 * 100_000L, chips.spent)
        assertEquals(6 * 250_000L + 4 * 60_000L, chips.earned)
        assertTrue(ledger.smoothedPerHour < ledger.rawPerHour, "the recent losses weigh more: ${ledger.smoothedPerHour} vs ${ledger.rawPerHour}")

        // Held: advisory only.
        assertTrue(Chains.verdict(Chains.ledger(chain(hold = true, enrolledHoursAgo = 4), good + bad, now), now, null).keep)
        // Inside the minimum tenure: keep even when losing.
        assertTrue(Chains.verdict(Chains.ledger(chain(hold = false, enrolledHoursAgo = 1), bad, now), now, null).keep)
        // Clearly paying: keep.
        val paying = Chains.verdict(Chains.ledger(chain(hold = false, enrolledHoursAgo = 4), good, now), now, null)
        assertTrue(paying.keep, paying.reason)
        // Clearly losing after tenure: release exactly one ship, and not again inside the cooldown.
        val losingLedger = Chains.ledger(chain(hold = false, enrolledHoursAgo = 4), bad, now)
        val release = Chains.verdict(losingLedger, now, null)
        assertTrue(!release.keep && release.releaseShip != null, release.reason)
        val cooled = Chains.verdict(losingLedger, now, now.minus(Duration.ofMinutes(20)))
        assertTrue(cooled.keep && cooled.reason.contains("released 20 min ago"), cooled.reason)
        // A one-ship team below the line dissolves.
        val solo = Chains.verdict(Chains.ledger(chain(hold = false, enrolledHoursAgo = 4, ships = listOf("S-1")), bad, now), now, null)
        assertTrue(!solo.keep && solo.reason.contains("dissolve"), solo.reason)
    }

    @Test
    fun `a chain in the dead band between the release and keep lines is kept`() {
        // Team baseline 200k/h; chain at 150k/h is 75%: inside the band.
        val steady = (0 until 8).flatMap { i -> listOf(buy(230L - i * 30, 100_000), sale(225L - i * 30, 175_000)) }
        val verdict = Chains.verdict(Chains.ledger(chain(hold = false, enrolledHoursAgo = 4), steady, now), now, null)
        assertTrue(verdict.keep && verdict.reason.startsWith("in the dead band"), verdict.reason)
    }

    @Test
    fun `a bee works the legs in turn and its transactions carry the chain tag`() {
        val seed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))
        val chain = Chain("chips", legs, listOf(Fixtures.COMMAND_SHIP), mapOf(Fixtures.COMMAND_SHIP to 100_000.0), Instant.parse("2026-09-04T12:00:00Z").toString(), hold = true, reserve = 50_000)
        val plan = Plan(listOf(Assignment(Fixtures.COMMAND_SHIP, "feed", mapOf("chain" to "chips")))).withChain(chain)
        val report = SimRun(seed, plan, hours = 3).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val tagged = report.trace.tagged
        assertTrue(tagged.isNotEmpty() && tagged.all { it.second == "chips" })
        val goods = tagged.map { it.first.tradeSymbol }.toSet()
        assertTrue(goods.containsAll(setOf(TradeSymbol.SILICON_CRYSTALS, TradeSymbol.COPPER, TradeSymbol.MICROPROCESSORS)), goods.toString() + " phases: " + report.trace.phases.filter { it.first == Fixtures.COMMAND_SHIP }.map { it.second.phase + ": " + it.second.detail }.toString())
        val phases = report.trace.phaseNames(Fixtures.COMMAND_SHIP).distinct()
        assertTrue(phases.containsAll(listOf("travel to source", "buy", "travel to destination", "deliver")), phases.toString())
        assertNull(report.trace.tagged.firstOrNull { it.first.tradeSymbol == TradeSymbol.FUEL && it.second != "chips" })
    }
}

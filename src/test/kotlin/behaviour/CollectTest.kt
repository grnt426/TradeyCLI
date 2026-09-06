package behaviour

import engine.Event
import model.ship.Ship
import model.ship.components.Cargo
import plan.Assignment
import plan.Plan
import sim.Fixtures
import sim.SimRun
import sim.SimSeed
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Drones parked on rocks, a hauler collecting: the mining fleet as a health investment. */
class CollectTest {
    private fun pricedSeed(): SimSeed = SimSeed.load(File("src/test/resources/x1-th77-priced-seed.json"))

    /** A drone made from the frigate: small hold, small tank, slow, with the frigate's mining laser. */
    private fun drone(from: Ship, symbol: String): Ship = from.copy(
        symbol = symbol,
        registration = from.registration.copy(name = symbol),
        cargo = Cargo(capacity = 15, units = 0),
        fuel = from.fuel.copy(current = 80, capacity = 80),
        engine = from.engine.copy(speed = 9),
    )

    @Test
    fun `two parked drones fill their holds, the collector takes the ore and sells it, and the drones never move again`() {
        val seed = pricedSeed()
        val frigate = seed.ships.first { it.symbol == Fixtures.COMMAND_SHIP }
        val withDrones = seed.copy(ships = seed.ships + drone(frigate, "TRIPLEHAT-7") + drone(frigate, "TRIPLEHAT-8"))
        val plan = Plan(listOf(
            Assignment("TRIPLEHAT-7", "mineInPlace", mapOf("asteroid" to "X1-TH77-B11")),
            Assignment("TRIPLEHAT-8", "mineInPlace", mapOf("asteroid" to "X1-TH77-B9")),
            Assignment(Fixtures.COMMAND_SHIP, "collect", mapOf("minShare" to "0.5")),
        ))
        val report = SimRun(withDrones, plan, hours = 8).run()
        assertTrue(report.failures.isEmpty(), report.failures.toString())
        val transfers = report.trace.events.filterIsInstance<Event.Transferred>()
        assertTrue(transfers.size >= 2, "the collector took ore from the drones: $transfers")
        assertTrue(transfers.all { it.to == Fixtures.COMMAND_SHIP && it.from in setOf("TRIPLEHAT-7", "TRIPLEHAT-8") })
        val sold = report.trace.events.filterIsInstance<Event.Sold>().filter { it.ship == Fixtures.COMMAND_SHIP }
        assertTrue(sold.isNotEmpty(), "the ore was sold")
        // Each drone flew exactly one leg: to its rock.
        val legs = report.trace.events.filterIsInstance<Event.Activity>().filter { it.kind in setOf("cruise", "drift") }
        assertTrue(legs.count { it.ship == "TRIPLEHAT-7" } == 1 && legs.count { it.ship == "TRIPLEHAT-8" } == 1, legs.filter { it.ship != Fixtures.COMMAND_SHIP }.toString())
        val phases = report.trace.phaseNames("TRIPLEHAT-7")
        assertTrue("full" in phases, "a drone with a full hold waits instead of pulling: ${phases.distinct()}")
    }
}

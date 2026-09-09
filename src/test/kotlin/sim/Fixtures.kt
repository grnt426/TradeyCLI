package sim

import java.io.File

/** The real X1-TH77 as the store knew it on 2026-09-04: 76 waypoints, 25 markets, 3 shipyards, 2 ships. */
object Fixtures {
    val seedFile = File("src/test/resources/x1-th77-seed.json")

    fun seed(): SimSeed = SimSeed.load(seedFile)

    const val COMMAND_SHIP = "TRIPLEHAT-1"

    /** A heavy freighter made from the command ship: its frame, a 225 hold, no mounts, standing at [system]'s first waypoint. */
    fun heavy(symbol: String, system: String = SYSTEM): model.ship.Ship {
        val frigate = seed().ships.first { it.symbol == COMMAND_SHIP }
        return frigate.copy(symbol = symbol, frame = frigate.frame.copy(symbol = "FRAME_HEAVY_FREIGHTER", moduleSlots = 13), mounts = emptyList(),
            cargo = frigate.cargo.copy(capacity = 225), nav = frigate.nav.copy(systemSymbol = system, waypointSymbol = "$system-A1"))
    }
    const val PROBE = "TRIPLEHAT-2"
    const val SYSTEM = "X1-TH77"
    const val HQ = "X1-TH77-A1"
    /** Imports the common ores; 46 from headquarters, 307 from the metal asteroid. */
    const val ORE_MARKET = "X1-TH77-H50"
    /** An asteroid base 50 from the metal asteroid that exchanges every ore: one tank covers HQ -> rock -> here. */
    const val NEAR_MARKET = "X1-TH77-B7"
    const val METAL_ASTEROID = "X1-TH77-B9"
    const val DRONE_SHIPYARD = "X1-TH77-H51"
}

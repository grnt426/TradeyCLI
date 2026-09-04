package sim

import engine.Snapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import model.Agent
import model.ApiJson
import model.Shipyard
import model.market.Market
import model.ship.Ship
import model.system.System
import model.system.Waypoint
import storage.AgentStore
import java.io.File

/**
 * Everything the simulator needs to stand up a system: what the store knows after a boot. Saved
 * as JSON so a test can run against the real home system without a token, and so the seed can be
 * edited by hand.
 */
@Serializable
data class SimSeed(
    val resetDate: String,
    val agent: Agent,
    val system: System,
    val waypoints: List<Waypoint>,
    val markets: List<Market>,
    val shipyards: List<Shipyard>,
    val ships: List<Ship>,
) {
    val systemSymbol: String get() = system.symbol

    fun save(file: File) {
        file.parentFile?.mkdirs()
        file.writeText(ApiJson.encodeToString(this))
    }

    companion object {
        fun load(file: File): SimSeed = ApiJson.decodeFromString(file.readText())

        fun fromSnapshot(snapshot: Snapshot, system: String = snapshot.hqSystem ?: error("no home system")): SimSeed = SimSeed(
            resetDate = snapshot.resetDate ?: "sim",
            agent = snapshot.agent ?: error("no agent"),
            system = snapshot.systems[system] ?: error("system $system not loaded"),
            waypoints = snapshot.waypointsIn(system),
            markets = snapshot.marketsIn(system),
            shipyards = snapshot.shipyardsIn(system),
            ships = snapshot.ships.values.filter { it.nav.systemSymbol == system }.sortedBy { it.symbol },
        )

        /** Reads a seed straight out of an agent's store, without booting. */
        suspend fun fromStore(store: AgentStore): SimSeed {
            val agent = store.getAgent() ?: error("store has no agent")
            val systems = store.listSystems()
            val hq = agent.headquarters.substringBeforeLast('-')
            val system = systems.firstOrNull { it.symbol == hq } ?: error("store has no system $hq")
            return SimSeed(
                resetDate = store.resetDate,
                agent = agent,
                system = system,
                waypoints = store.listWaypoints(hq).sortedBy { it.symbol },
                markets = store.listMarkets(hq).sortedBy { it.symbol },
                shipyards = store.listShipyards(hq).sortedBy { it.symbol },
                ships = store.listShips().filter { it.nav.systemSymbol == hq }.sortedBy { it.symbol },
            )
        }
    }
}

package model

import api.RequestPacer
import api.SpaceTradersApi
import client.SpaceTradersClient
import engine.Engine
import kotlinx.coroutines.CoroutineScope
import model.GameState.GAME_API
import model.GameState.shipsToScripts
import model.contract.Contract
import model.market.Market
import model.ship.Ship
import model.system.OrbitalNames
import model.system.System
import model.system.Waypoint
import script.ScriptExecutor

const val DEFAULT_PROF_DIR = "profile"
const val DEFAULT_PROF_FILE = "$DEFAULT_PROF_DIR/profile.settings.json"

/**
 * Facade over the [Engine] for code that predates it: the parked script layer and a few model
 * helpers. New code reads `GameState.engine.state` (a snapshot) or calls the engine directly.
 * The maps here are the engine's live maps; the by-system views are computed on each access and
 * writes to them are discarded, which the parked scripts cannot notice while switched off.
 */
object GameState {

    const val GAME_API = "https://api.spacetraders.io/v2/"

    private val engineDelegate = lazy { Engine() }

    /** The one engine of this process. Line mode and the TUI both drive it. */
    val engine: Engine by engineDelegate

    val engineScope: CoroutineScope get() = engine.scope
    val pacer: RequestPacer get() = engine.pacer
    val api: SpaceTradersApi get() = engine.api ?: error("Not connected to the API yet")

    lateinit var profData: ProfileData

    // we can only ever have one contract for now
    var contract: Contract? = null
    var commandShip: Ship? = null
    var engineeredAsteroid: String = ""

    val agent: Agent get() = engine.world.agent ?: error("Agent not loaded yet")
    val systems: MutableMap<String, System> get() = engine.world.systems
    val waypoints: MutableMap<String, Waypoint> get() = engine.world.waypoints
    val shipyards: MutableMap<String, Shipyard> get() = engine.world.shipyards
    val ships: MutableMap<String, Ship> get() = engine.world.ships
    val markets: MutableMap<String, Market> get() = engine.world.markets
    val marketsBySystem: MutableMap<String, MutableList<Market>>
        get() = engine.world.markets.values.groupByTo(mutableMapOf()) { OrbitalNames.getSectorSystem(it.symbol) }
    val shipyardsBySystem: MutableMap<String, MutableList<Shipyard>>
        get() = engine.world.shipyards.values.groupByTo(mutableMapOf()) { OrbitalNames.getSectorSystem(it.symbol) }

    val shipsToScripts: MutableMap<Ship, ScriptExecutor<*>> = mutableMapOf()
    val scriptsRunning = mutableListOf<ScriptExecutor<*>>()

    /**
     * Master switch for ship automation. Off pending the scripting overhaul (see
     * docs/codebase-review-2026-09.md, sections 4.4 and 7). While it is off nothing constructs,
     * resumes or runs a script, and the legacy request queue is never started.
     */
    var scriptsEnabled = false

    fun getHqSystem(): System =
        engine.world.hqSystemSymbol()?.let { systems[it] } ?: error("Home system not loaded yet")

    /** Stops the engine and closes every client. Safe to call before anything was started. */
    fun shutdown() {
        if (engineDelegate.isInitialized()) engine.shutdown()
        SpaceTradersClient.closeIfOpen()
    }
}

fun api(params: String): String = "$GAME_API$params"

fun getScriptForShip(ship: Ship): ScriptExecutor<*>? = shipsToScripts[ship]

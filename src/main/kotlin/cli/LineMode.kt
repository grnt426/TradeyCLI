package cli

import engine.Engine
import engine.Snapshot
import kotlinx.coroutines.runBlocking
import model.GameState
import model.exceptions.BootFailure
import model.ship.ShipNavStatus
import model.system.OrbitalNames
import startup.BootManager
import java.io.PrintStream
import java.time.Instant

/**
 * The client without the screen: one command per invocation, or a `repl` that reads commands from
 * standard input until end of file. Plain text out, so it can be driven from any terminal, piped,
 * and asserted on in tests.
 *
 * Usage: `tradey [--agent SYMBOL] [--refresh] <command> [args]`
 */
class LineMode(
    private val engine: Engine = GameState.engine,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val boot: suspend (agent: String?, progress: (String) -> Unit) -> Unit = { agent, progress ->
        BootManager.normalStart(agentSymbol = agent, progress = progress)
    },
) {
    private var refresh = false

    fun run(args: List<String>): Int {
        val options = mutableMapOf<String, String>()
        val positional = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--refresh" -> refresh = true
                a == "--agent" && i + 1 < args.size -> options["agent"] = args[++i]
                a.startsWith("--agent=") -> options["agent"] = a.substringAfter('=')
                a == "--help" || a == "-h" -> { printHelp(); return 0 }
                a.startsWith("--") -> { err.println("Unknown option $a"); printHelp(); return 1 }
                else -> positional += a
            }
            i++
        }
        val command = positional.firstOrNull() ?: run { printHelp(); return 1 }
        if (command == "help") { printHelp(); return 0 }
        if (command !in COMMANDS) { err.println("Unknown command '$command'"); printHelp(); return 1 }

        return runBlocking {
            try {
                boot(options["agent"]) { step -> err.println("  $step") }
            } catch (e: BootFailure) {
                err.println("Boot failed: ${e.message}")
                return@runBlocking 2
            }
            if (command == "repl") repl() else dispatch(command, positional.drop(1))
        }
    }

    private suspend fun repl(): Int {
        err.println("Ready. Commands: ${COMMANDS.filter { it != "repl" }.joinToString(" ")}. Blank line or 'quit' exits.")
        while (true) {
            err.print("> ")
            err.flush()
            val line = readlnOrNull()?.trim() ?: break
            if (line.isEmpty() || line == "quit" || line == "exit") break
            val parts = line.split(Regex("\\s+"))
            refresh = "--refresh" in parts
            val words = parts.filterNot { it.startsWith("--") }
            val cmd = words.first()
            if (cmd == "help") { printHelp(); continue }
            if (cmd !in COMMANDS || cmd == "repl") { err.println("Unknown command '$cmd'"); continue }
            try {
                dispatch(cmd, words.drop(1))
            } catch (e: Exception) {
                err.println("${e::class.simpleName}: ${e.message}")
            }
        }
        return 0
    }

    private suspend fun dispatch(command: String, args: List<String>): Int {
        when (command) {
            "status" -> status()
            "agent" -> agent()
            "ships" -> ships()
            "waypoints" -> waypoints(args.firstOrNull())
            "markets" -> markets(args.firstOrNull())
            "market" -> {
                val symbol = args.firstOrNull() ?: run { err.println("market needs a waypoint symbol"); return 1 }
                market(symbol.uppercase())
            }
            "shipyards" -> shipyards(args.firstOrNull())
        }
        return 0
    }

    private suspend fun status() {
        if (refresh) engine.refreshAgent()
        val s = engine.snapshot
        val agent = s.agent
        table(
            listOf("field", "value"),
            listOf(
                listOf("agent", agent?.symbol ?: "?"),
                listOf("credits", agent?.credits?.toString() ?: "?"),
                listOf("headquarters", agent?.headquarters ?: "?"),
                listOf("faction", agent?.startingFaction ?: "?"),
                listOf("ships", s.ships.size.toString()),
                listOf("home system", s.hqSystem ?: "?"),
                listOf("waypoints loaded", s.waypoints.size.toString()),
                listOf("markets loaded", s.markets.size.toString()),
                listOf("reset", s.resetDate ?: "?"),
                listOf("next reset", s.nextReset ?: "?"),
                listOf("requests this run", engine.api?.client?.stats?.requests?.get()?.toString() ?: "0"),
            ),
        )
    }

    private suspend fun agent() {
        val agent = if (refresh) engine.refreshAgent() else engine.snapshot.agent ?: return err.println("Agent not loaded")
        table(
            listOf("field", "value"),
            listOf(
                listOf("symbol", agent.symbol),
                listOf("headquarters", agent.headquarters),
                listOf("credits", agent.credits.toString()),
                listOf("startingFaction", agent.startingFaction),
                listOf("shipCount", agent.shipCount.toString()),
            ),
        )
    }

    private suspend fun ships() {
        val fleet = if (refresh) engine.refreshShips() else engine.snapshot.ships.values.sortedBy { it.symbol }
        val now = Instant.now()
        table(
            listOf("ship", "role", "frame", "status", "waypoint", "fuel", "cargo", "cooldown"),
            fleet.map { ship ->
                val arrival = ship.nav.route.arrival
                val status = when {
                    ship.nav.status == ShipNavStatus.IN_TRANSIT && arrival.isAfter(now) ->
                        "IN_TRANSIT (${arrival.epochSecond - now.epochSecond}s)"
                    else -> ship.nav.status.name
                }
                listOf(
                    ship.symbol,
                    ship.registration.role.name,
                    ship.frame.symbol.removePrefix("FRAME_"),
                    status,
                    ship.nav.waypointSymbol,
                    "${ship.fuel.current}/${ship.fuel.capacity}",
                    "${ship.cargo.units}/${ship.cargo.capacity}",
                    ship.cooldown.remainingSeconds.takeIf { it > 0 }?.let { "${it}s" } ?: "-",
                )
            },
        )
    }

    private suspend fun waypoints(systemArg: String?) {
        val system = systemOrHome(systemArg) ?: return
        val list = loaded(system).waypointsIn(system)
        table(
            listOf("waypoint", "type", "x", "y", "traits"),
            list.map { w -> listOf(w.symbol, w.type.name, w.x.toString(), w.y.toString(), w.traits.joinToString(",") { it.symbol.name }) },
        )
    }

    private suspend fun markets(systemArg: String?) {
        val system = systemOrHome(systemArg) ?: return
        val list = loaded(system).marketsIn(system)
        table(
            listOf("market", "imports", "exports", "exchange", "priced goods"),
            list.map { m ->
                listOf(
                    m.symbol,
                    m.imports.joinToString(",") { it.symbol.name },
                    m.exports.joinToString(",") { it.symbol.name },
                    m.exchange.joinToString(",") { it.symbol.name },
                    m.tradeGoods.size.toString(),
                )
            },
        )
    }

    private suspend fun market(symbol: String) {
        val market = if (refresh || engine.snapshot.markets[symbol] == null) engine.refreshMarket(symbol) else engine.snapshot.markets.getValue(symbol)
        out.println("${market.symbol}  imports: ${market.imports.joinToString(",") { it.symbol.name }}  exports: ${market.exports.joinToString(",") { it.symbol.name }}")
        if (market.tradeGoods.isEmpty()) {
            out.println("No prices: a ship must be at the waypoint to see them.")
            return
        }
        table(
            listOf("good", "type", "supply", "activity", "buy", "sell", "volume"),
            market.tradeGoods.sortedBy { it.symbol.name }.map { g ->
                listOf(g.symbol.name, g.type.name, g.supply.name, g.activity?.name ?: "-", g.purchasePrice.toString(), g.sellPrice.toString(), g.tradeVolume.toString())
            },
        )
    }

    private suspend fun shipyards(systemArg: String?) {
        val system = systemOrHome(systemArg) ?: return
        val list = loaded(system).shipyardsIn(system)
        table(
            listOf("shipyard", "sells", "fee"),
            list.map { y -> listOf(y.symbol, y.shipTypes.joinToString(",") { it.type.name.removePrefix("SHIP_") }, y.modificationsFee.toString()) },
        )
    }

    private fun systemOrHome(arg: String?): String? {
        val system = arg?.uppercase()?.let { if (it.count { c -> c == '-' } >= 2) OrbitalNames.getSectorSystem(it) else it }
            ?: engine.snapshot.hqSystem
        if (system == null) err.println("No system given and no home system known")
        return system
    }

    /** Waits for a system's contents to be in the snapshot, fetching them if this is the first look. */
    private suspend fun loaded(system: String): Snapshot {
        engine.ensureSystem(system)
        if (refresh) engine.refreshWaypoints(system)
        engine.awaitSystem(system)
        return engine.snapshot
    }

    private fun table(headers: List<String>, rows: List<List<String>>) {
        val widths = headers.indices.map { c -> maxOf(headers[c].length, rows.maxOfOrNull { it[c].length } ?: 0) }
        fun line(cells: List<String>) = cells.mapIndexed { c, v -> v.padEnd(widths[c]) }.joinToString("  ").trimEnd()
        out.println(line(headers))
        out.println(widths.joinToString("  ") { "-".repeat(it) })
        rows.forEach { out.println(line(it)) }
        if (rows.isEmpty()) out.println("(none)")
    }

    private fun printHelp() {
        err.println(
            """
            Usage: tradey [--agent SYMBOL] [--refresh] <command> [args]

              status               agent, fleet size, reset date, request count
              agent                the agent record
              ships                the fleet
              waypoints [SYSTEM]   waypoints of a system (default: home)
              markets [SYSTEM]     markets of a system with their imports and exports
              market WAYPOINT      prices at one market (needs a ship there for prices)
              shipyards [SYSTEM]   shipyards of a system and what they sell
              repl                 read commands from standard input until EOF

            --agent picks an agent folder under profile/agents; --refresh re-fetches instead of using cached data.
            With no arguments the terminal dashboard starts instead.
            """.trimIndent()
        )
    }

    companion object {
        val COMMANDS = listOf("status", "agent", "ships", "waypoints", "markets", "market", "shipyards", "repl")
    }
}

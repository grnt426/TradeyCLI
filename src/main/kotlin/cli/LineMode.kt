package cli

import api.ApiClient
import api.SpaceTradersApi
import app.App
import behaviour.Behaviours
import behaviour.decisions.Intentions
import behaviour.decisions.Mining
import engine.AcceleratedClock
import engine.Engine
import engine.Event
import engine.Snapshot
import api.RequestPacer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import model.exceptions.BootFailure
import model.ship.ShipNavStatus
import model.ship.ShipType
import model.system.OrbitalNames
import plan.Assignment
import plan.Chain
import plan.FleetGoal
import plan.Leg
import plan.Plan
import plan.RunLock
import plan.Supervisor
import kotlinx.coroutines.delay
import sim.FakeServer
import sim.SimReport
import sim.SimRules
import sim.SimRun
import sim.SimSeed
import sim.SimUniverse
import startup.BootManager
import storage.AgentStore
import storage.Layout
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * The client without the screen: one command per invocation, or a `repl` that reads commands from
 * standard input until end of file. Plain text out, so it can be driven from any terminal, piped,
 * and asserted on in tests.
 *
 * Usage: `tradey [--agent SYMBOL] [--refresh] [--sim[=FACTOR]] <command> [args]`
 */
class LineMode(
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    /** Builds the engine; the default is the process-wide one, or a simulator-backed one with `--sim`. */
    private val engineFactory: (SimOptions?) -> Engine = { sim -> if (sim == null) App.engine else simEngine(sim) },
    private val boot: suspend (engine: Engine, sim: SimOptions?, agent: String?, progress: (String) -> Unit) -> Unit = { engine, sim, agent, progress ->
        if (sim == null) BootManager.normalStart(agentSymbol = agent, progress = progress) else engine.boot("sim", progress)
    },
) {
    private var refresh = false
    private lateinit var engine: Engine
    private var agentOption: String? = null

    /** `--sim`: the engine runs against a fake server seeded from the agent's store, at [factor] times real speed. */
    data class SimOptions(val agent: String?, val factor: Double)

    fun run(args: List<String>): Int {
        val options = mutableMapOf<String, String>()
        val positional = mutableListOf<String>()
        var sim: SimOptions? = null
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--refresh" -> refresh = true
                a == "--agent" && i + 1 < args.size -> options["agent"] = args[++i]
                a.startsWith("--agent=") -> options["agent"] = a.substringAfter('=')
                a == "--sim" -> sim = SimOptions(null, 60.0)
                a.startsWith("--sim=") -> sim = SimOptions(null, a.substringAfter('=').toDoubleOrNull() ?: 60.0)
                a == "--help" || a == "-h" -> { printHelp(); return 0 }
                positional.isEmpty() && a.startsWith("--") -> { err.println("Unknown option $a"); printHelp(); return 1 }
                else -> positional += a
            }
            i++
        }
        val command = positional.firstOrNull() ?: run { printHelp(); return 1 }
        if (command == "help") { printHelp(); return 0 }
        if (command == "behaviours") { err.println(Behaviours.usage()); return 0 }
        if (command !in COMMANDS) { err.println("Unknown command '$command'"); printHelp(); return 1 }
        agentOption = options["agent"]
        sim = sim?.copy(agent = agentOption)

        if (command == "sim") return runBlocking { simulate(positional.drop(1)) }
        if (command == "register") return runBlocking { register(positional.drop(1)) }
        if (command == "catalog") return runBlocking { catalog(positional.drop(1)) }

        return runBlocking {
            engine = engineFactory(sim)
            try {
                boot(engine, sim, agentOption) { step -> err.println("  $step") }
            } catch (e: BootFailure) {
                err.println("Boot failed: ${e.message}")
                return@runBlocking 2
            }
            if (command == "repl") repl() else dispatch(command, positional.drop(1))
        }
    }

    private suspend fun repl(): Int {
        err.println("Ready. Commands: ${COMMANDS.filter { it != "repl" && it != "sim" }.joinToString(" ")}. Blank line or 'quit' exits.")
        while (true) {
            err.print("> ")
            err.flush()
            val line = readlnOrNull()?.trim() ?: break
            if (line.isEmpty() || line == "quit" || line == "exit") break
            val parts = line.split(Regex("\\s+"))
            refresh = "--refresh" in parts
            val words = parts.filterNot { it == "--refresh" }
            val cmd = words.first()
            if (cmd == "help") { printHelp(); continue }
            if (cmd !in COMMANDS || cmd == "repl" || cmd == "sim") { err.println("Unknown command '$cmd'"); continue }
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
            "asteroids" -> asteroids(args)
            "trades" -> trades(args)
            "intentions" -> intentions()
            "contracts" -> contracts()
            "plan" -> plan()
            "assign" -> return assign(args)
            "unassign" -> return unassign(args)
            "goal" -> return goal(args)
            "chain" -> return chain(args)
            "phase" -> return phase(args)
            "summary" -> summary()
            "race" -> race(args)
            "gate" -> gate(args)
            "jumpgate" -> jumpgate(args)
            "jump" -> return jump(args)
            "run" -> return runPlan(args)
            "buy" -> return buy(args)
            "extractions" -> extractions()
        }
        return 0
    }

    // Reads

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
                listOf("markets priced", s.markets.values.count { it.hasPrices }.toString()),
                listOf("reset", s.resetDate ?: "?"),
                listOf("next reset", s.nextReset ?: "?"),
                listOf("requests this run", engine.apiClient?.stats?.requests?.get()?.toString() ?: "0"),
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
        val snap = engine.snapshot
        val now = engine.clock.now()
        table(
            listOf("ship", "role", "frame", "status", "waypoint", "fuel", "cargo", "cooldown", "behaviour"),
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
                    ship.cooldown.expiresAt(now)?.let { "${it.epochSecond - now.epochSecond}s" } ?: "-",
                    snap.shipStatus[ship.symbol]?.let { "${it.behaviour}: ${it.phase} ${it.detail}".trim() } ?: "-",
                )
            },
        )
    }

    private suspend fun waypoints(systemArg: String?) {
        val system = systemOrHome(systemArg) ?: return
        val list = loaded(system).waypointsIn(system)
        table(
            listOf("waypoint", "type", "x", "y", "traits", "modifiers"),
            list.map { w -> listOf(w.symbol, w.type.name, w.x.toString(), w.y.toString(), w.traits.joinToString(",") { it.symbol.name }, w.modifiers.joinToString(",") { it.symbol }) },
        )
    }

    private suspend fun markets(systemArg: String?) {
        val system = systemOrHome(systemArg) ?: return
        val list = loaded(system).marketsIn(system)
        table(
            listOf("market", "imports", "exports", "exchange", "priced goods", "read"),
            list.map { m ->
                listOf(
                    m.symbol,
                    m.imports.joinToString(",") { it.symbol.name },
                    m.exports.joinToString(",") { it.symbol.name },
                    m.exchange.joinToString(",") { it.symbol.name },
                    m.tradeGoods.size.toString(),
                    if (m.hasPrices) time(m.lastRead) else "-",
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
            listOf("shipyard", "sells", "prices", "fee"),
            list.map { y ->
                listOf(
                    y.symbol,
                    y.shipTypes.joinToString(",") { it.type.name.removePrefix("SHIP_") },
                    y.ships.joinToString(",") { "${it.type.name.removePrefix("SHIP_")}=${it.purchasePrice}" }.ifEmpty { "-" },
                    y.modificationsFee.toString(),
                )
            },
        )
    }

    /** The ranking: every asteroid with the market that pays best for what it yields. */
    private suspend fun asteroids(args: List<String>) {
        val shipArg = args.indexOf("--ship").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.uppercase() }
        val system = systemOrHome(args.firstOrNull()?.takeUnless { it.startsWith("--") || it == shipArg }) ?: return
        val snap = loaded(system)
        val ship = (shipArg?.let { snap.ships[it] } ?: snap.ships.values.filter { it.canMine || it.canSiphon }.minByOrNull { it.symbol })
            ?: return err.println("No ship with a mining laser or siphon; name one with --ship")
        val plans = Mining.bestPerAsteroid(Mining.rank(snap, ship, engine.clock.now()))
        err.println("Ranked for ${ship.symbol} (laser strength ${ship.miningStrength}, cargo ${ship.cargo.capacity}, speed ${ship.engine.speed}). Prices marked ~ are guesses: no ship has read that market yet.")
        table(
            listOf("asteroid", "deposits", "market", "cr/h", "cr/unit", "sellable", "cycle", "dist", "fuel", "return", "risk", "notes / observed"),
            plans.map { p ->
                listOf(
                    p.asteroid.symbol,
                    if (p.asteroid.isSiphonable) "GAS" else p.asteroid.traits.map { it.symbol.name }.filter { it.endsWith("DEPOSITS") || it == "ICE_CRYSTALS" || it == "FROZEN" }.joinToString(",") { it.removeSuffix("_DEPOSITS") },
                    p.market.symbol,
                    (if (p.estimated) "~" else "") + p.creditsPerHour.toInt(),
                    "%.0f".format(p.valuePerUnit),
                    "${(p.tradedShare * 100).toInt()}%",
                    "${p.cycleSeconds / 60}m",
                    p.distance.toInt().toString(),
                    p.fuelPerCycle.toString(),
                    when (val r = p.returnLeg) { is behaviour.decisions.ReturnLeg.Via -> "via ${r.via.symbol}"; behaviour.decisions.ReturnLeg.Drift -> "DRIFT"; else -> "cruise" },
                    "%.2f".format(p.risk),
                    (p.asteroid.modifiers.map { it.symbol } + p.riskNotes).distinct().joinToString(", "),
                )
            },
        )
    }

    /** The trade ranking: what to buy where and sell where, from where the ship is. */
    private suspend fun trades(args: List<String>) {
        val shipArg = option(args, "--ship")?.uppercase()
        val snap = engine.snapshot
        val ship = (shipArg?.let { snap.ships[it] } ?: snap.ships.values.filter { it.cargo.capacity > 0 }.maxByOrNull { it.cargo.capacity })
            ?: return err.println("No ship with a cargo hold; name one with --ship")
        val plans = behaviour.decisions.Trading.rank(snap, ship, engine.clock.now())
        err.println("Ranked for ${ship.symbol} (cargo ${ship.cargo.capacity}, speed ${ship.engine.speed}, ${snap.agent?.credits} credits) from ${ship.nav.waypointSymbol}; prices as last read, impact of our own trades discounted; score = cr/h weighted by market health (knowledge.MarketAssumptions).")
        table(
            listOf("good", "buy at", "price", "sell at", "price", "units", "profit", "cr/h", "score", "cycle", "legs", "health"),
            plans.take(args.indexOf("--all").let { if (it >= 0) plans.size else 25 }).map { p ->
                listOf(p.good.name, p.source.symbol, p.buyPrice.toString(), p.destination.symbol, p.sellPrice.toString(), p.units.toString(), p.profit.toString(), p.creditsPerHour.toInt().toString(), p.score.toInt().toString(), "${p.cycleSeconds / 60}m", "${p.legToSource.toInt()}+${p.legToDestination.toInt()}", p.health)
            },
        )
    }

    /** The dashboard's credits graph and intentions panel, as text. */
    private fun intentions() {
        val snap = engine.snapshot
        val now = engine.clock.now()
        snap.plan?.phase?.let { out.println("[neutral] Phase ${knowledge.Strategy.describe(it)}") }
        val trend = behaviour.decisions.CreditsTrend.trend(snap.creditsHistory, now)
        behaviour.decisions.Intentions.describe(snap, now, trend).forEach { out.println("[${it.tone.name.lowercase()}] ${it.text}") }
        val graph = behaviour.decisions.CreditsTrend.graph(snap.creditsHistory, now)
        if (snap.creditsHistory.isEmpty()) return
        out.println()
        val rows = 6
        for (row in 0 until rows) {
            val line = StringBuilder(graph.label(row, rows).padStart(7) + " ")
            graph.columns.forEach { c ->
                val v = c.value
                // ASCII only: Windows consoles without UTF-8 turn block characters into question marks
                line.append(if (v == null) " " else when (graph.glyphIndex(v, row, rows)) { 0 -> " "; 8 -> if (c.projected) "+" else "#"; else -> if (c.projected) "." else "=" })
            }
            out.println(line)
        }
        out.println(" ".repeat(8) + graph.axis())
        out.println(" ".repeat(8) + graph.axisLabels())
        out.println("history (#) then projection (+); now ${snap.agent?.credits}, ${trend.perHour.toLong()}/h, in ${graph.projectionSpan.toMinutes()}m about ${graph.projectedEnd.toLong()}")
    }

    /** Every contract seen this reset with what it paid and what it cost, oldest first: do they grow? */
    private suspend fun contracts() {
        val store = engine.store ?: return err.println("No store open")
        val records = store.listContractRecords()
        table(
            listOf("contract", "type", "faction", "deliver", "to", "on accept", "on fulfil", "cost", "profit", "accepted", "fulfilled", "deadline"),
            records.map { r ->
                val c = r.contract
                val term = c.terms.deliver.firstOrNull()
                listOf(
                    c.id.takeLast(8), c.type, c.factionSymbol,
                    term?.let { "${it.unitsFulfilled}/${it.unitsRequired} ${it.tradeSymbol}" } ?: "-", term?.destinationSymbol ?: "-",
                    c.terms.payment.onAccepted.toString(), c.terms.payment.onFulfilled.toString(), r.cost.toString(), r.profit.toString(),
                    r.acceptedAt?.let { time(it) } ?: (if (c.accepted) "yes" else "-"), r.fulfilledAt?.let { time(it) } ?: (if (c.fulfilled) "yes" else "-"),
                    c.terms.deadline.take(16),
                )
            },
        )
    }

    /** `register SYMBOL FACTION`: a new agent on this account, its token saved under profile/agents; the active agent is unchanged. */
    private suspend fun register(args: List<String>): Int {
        val symbol = args.getOrNull(0)?.uppercase() ?: run { err.println("register SYMBOL FACTION"); return 1 }
        val faction = args.getOrNull(1)?.uppercase()?.let { runCatching { model.faction.FactionSymbol.valueOf(it) }.getOrNull() }
            ?: run { err.println("register SYMBOL FACTION; factions: ${model.faction.FactionSymbol.entries.joinToString(",")}"); return 1 }
        return try {
            val assigned = BootManager.registerOnly(symbol, faction)
            out.println("registered $assigned with $faction; use --agent $assigned")
            0
        } catch (e: BootFailure) {
            err.println("Registration failed: ${e.message}"); 2
        }
    }

    /**
     * `catalog [ships|parts]`: every ship listing and every part seen for sale by any agent on this
     * account, from their stores, no network. What is out there, where, and at what price.
     */
    private suspend fun catalog(args: List<String>): Int {
        val what = args.firstOrNull() ?: "all"
        val ships = mutableListOf<List<String>>()
        val parts = mutableListOf<List<String>>()
        for (symbol in Layout.listAgents()) {
            val db = Layout.latestDatabase(symbol) ?: continue
            AgentStore.open(Layout.agentDir(symbol), symbol, Layout.resetDateOf(db)).use { store ->
                store.listShipyards().forEach { yard ->
                    yard.ships.forEach { s ->
                        ships += listOf(
                            s.type.name.removePrefix("SHIP_"), yard.symbol, s.purchasePrice.toString(), s.frame.symbol.removePrefix("FRAME_"),
                            s.engine.symbol.removePrefix("ENGINE_"), s.engine.speed.toString(), s.frame.fuelCapacity.toString(),
                            s.modules.filter { it.symbol.startsWith("MODULE_CARGO_HOLD") }.sumOf { it.capacity }.toString(),
                            s.modules.joinToString(",") { it.symbol.removePrefix("MODULE_") }, s.mounts.joinToString(",") { it.symbol.name.removePrefix("MOUNT_") }, symbol,
                        )
                    }
                }
                store.listMarkets().forEach { m ->
                    (m.imports + m.exports + m.exchange).map { it.symbol }.filter { g -> PART_PREFIXES.any { g.name.startsWith(it) } }.forEach { g ->
                        val price = m.good(g)
                        parts += listOf(g.name, m.symbol, m.typeOf(g)?.name ?: "-", price?.purchasePrice?.toString() ?: "-", price?.sellPrice?.toString() ?: "-", price?.supply?.name ?: "-", symbol)
                    }
                }
            }
        }
        if (what == "all" || what == "ships") {
            table(listOf("ship", "shipyard", "price", "frame", "engine", "speed", "fuel", "hold", "modules", "mounts", "seen by"), ships.sortedWith(compareBy({ it[0] }, { it[1] })))
            if (what == "all") out.println()
        }
        if (what == "all" || what == "parts") {
            table(listOf("part", "market", "type", "buy", "sell", "supply", "seen by"), parts.distinctBy { it[0] + it[1] }.sortedWith(compareBy({ it[0] }, { it[1] })))
        }
        return 0
    }

    /** A gate's connections and whether it is finished. */
    private suspend fun jumpgate(args: List<String>) {
        val snap = engine.snapshot
        val gate = args.firstOrNull()?.uppercase() ?: snap.waypointsIn(snap.hqSystem ?: return).firstOrNull { it.type == model.system.WaypointType.JUMP_GATE }?.symbol
            ?: return err.println("No jump gate in ${snap.hqSystem}; name one")
        val waypoint = snap.waypoints[gate]
        out.println("$gate: ${if (waypoint?.isUnderConstruction == true) "UNDER CONSTRUCTION (see `gate`)" else "complete"}")
        val info = engine.verbs().jumpGate(gate)
        table(listOf("connected gate", "system"), info.connections.map { listOf(it, OrbitalNames.getSectorSystem(it)) })
    }

    /** `jump SHIP GATE`: through the gate the ship is at, to a connected gate. */
    private suspend fun jump(args: List<String>): Int {
        if (args.size < 2) { err.println("jump SHIP DESTINATION_GATE"); return 1 }
        val ship = engine.verbs().jump(args[0].uppercase(), args[1].uppercase())
        engine.refreshShips()
        out.println("${ship.symbol} is at ${ship.nav.waypointSymbol} in ${ship.nav.systemSymbol}; credits ${engine.snapshot.agent?.credits}")
        return 0
    }

    /** The construction site's bill, what we have delivered, what it cost, and what finishing would cost at today's prices. */
    private suspend fun gate(args: List<String>) {
        val store = engine.store ?: return err.println("No store open")
        val snap = engine.snapshot
        val site = args.firstOrNull()?.uppercase() ?: snap.waypointsIn(snap.hqSystem ?: return).firstOrNull { it.isUnderConstruction }?.symbol
            ?: return err.println("Nothing under construction in ${snap.hqSystem}; name a waypoint")
        val construction = engine.verbs().construction(site)
        val supplies = store.listSupplies(site)
        val purchases = store.listChainTransactions("gate:$site").filter { it.type == model.market.TransactionType.PURCHASE && it.tradeSymbol != model.market.TradeSymbol.FUEL }
        val fuel = (store.listChainTransactions("gate:$site") + store.listChainTransactions("nurse:$site")).filter { it.tradeSymbol == model.market.TradeSymbol.FUEL }.sumOf { it.totalPrice.toLong() }
        val nursing = store.listChainTransactions("nurse:$site").filter { it.tradeSymbol != model.market.TradeSymbol.FUEL }
        val nursed = nursing.filter { it.type == model.market.TransactionType.PURCHASE }.sumOf { it.totalPrice.toLong() } - nursing.filter { it.type == model.market.TransactionType.SELL }.sumOf { it.totalPrice.toLong() }
        out.println("$site: ${if (construction.isComplete) "COMPLETE" else "under construction"}; fuel spent on the haul ${Intentions.format(fuel)}; nursing the producers cost ${Intentions.format(nursed)} net")
        table(
            listOf("material", "required", "fulfilled", "we delivered", "we bought", "spent", "avg", "cheapest now", "to finish at that price"),
            construction.materials.map { m ->
                val ours = supplies.filter { it.good == m.tradeSymbol }.sumOf { it.units }
                val bought = purchases.filter { it.tradeSymbol == m.tradeSymbol }
                val spent = bought.sumOf { it.totalPrice.toLong() }
                val units = bought.sumOf { it.units }
                val cheapest = snap.marketsIn(snap.hqSystem ?: "").mapNotNull { it.good(m.tradeSymbol)?.purchasePrice }.minOrNull()
                listOf(
                    m.tradeSymbol.name, m.required.toString(), m.fulfilled.toString(), ours.toString(), units.toString(), Intentions.format(spent),
                    if (units > 0) (spent / units).toString() else "-", cheapest?.toString() ?: "-",
                    cheapest?.let { Intentions.format((m.required - m.fulfilled) * it) } ?: "-",
                )
            },
        )
        // The producers' health: whether the hauler may buy now, and which inputs it should feed instead.
        val rules = knowledge.MarketAssumptions()
        construction.materials.filter { it.required > it.fulfilled }.forEach { m ->
            val producers = snap.marketsIn(snap.hqSystem ?: "").mapNotNull { market -> market.good(m.tradeSymbol)?.let { market to it } }
                .filter { (_, g) -> g.type == model.market.TradeGoodType.EXPORT }
            producers.forEach { (market, listing) ->
                out.println("${m.tradeSymbol} at ${market.symbol}: ${knowledge.MarketHealth.explain(listing, rules)}; read ${age(market.lastRead)} ago")
                val inputs = knowledge.MarketHealth.starvedInputs(market, m.tradeSymbol)
                if (inputs.isNotEmpty()) table(
                    listOf("input", "supply/activity", "pays", "cheapest healthy source", "price", "source health"),
                    inputs.map { input ->
                        val source = snap.marketsIn(snap.hqSystem ?: "").filter { it.symbol != market.symbol }
                            .mapNotNull { s -> s.good(input.symbol)?.let { s to it } }
                            .filter { (_, o) -> !knowledge.MarketHealth.starved(o, rules) }
                            .minByOrNull { (_, o) -> o.purchasePrice }
                        listOf(input.symbol.name, knowledge.MarketHealth.describe(input), input.sellPrice.toString(), source?.first?.symbol ?: "-", source?.second?.purchasePrice?.toString() ?: "-", source?.second?.let { knowledge.MarketHealth.describe(it) } ?: "-")
                    },
                )
            }
        }
    }

    private fun age(at: java.time.Instant?): String {
        if (at == null) return "never"
        val minutes = java.time.Duration.between(at, engine.clock.now()).toMinutes()
        return if (minutes < 90) "${minutes}m" else "${minutes / 60}h"
    }

    private suspend fun extractions() {
        val store = engine.store ?: return err.println("No store open")
        val list = store.listExtractions()
        table(
            listOf("at", "ship", "waypoint", "good", "units", "survey", "modifiers"),
            list.map { e -> listOf(time(e.at), e.ship, e.waypoint, e.good.name, e.units.toString(), e.surveySignature?.takeLast(8) ?: "-", e.modifiers.joinToString(",")) },
        )
    }

    // The plan

    private fun planFile(): File = Layout.planFile(engine.snapshot.agent?.symbol ?: agentOption ?: "UNKNOWN")

    private fun plan() {
        val plan = Plan.load(planFile())
        val snap = engine.snapshot
        table(
            listOf("ship", "behaviour", "params", "problems"),
            plan.assignments.map { a ->
                listOf(a.ship, a.behaviour, a.params.entries.joinToString(" ") { (k, v) -> "--$k $v" }, plan.copy(assignments = listOf(a)).validate(snap).joinToString("; "))
            },
        )
        out.println("phase ${plan.phase}: ${knowledge.Strategy.describe(plan.phase)}")
        plan.goals.credits?.let { out.println("goal: $it credits") }
        plan.goals.fleet.forEach { out.println("fleet goal: ${it.count} x ${it.type}, keeping ${it.reserve} credits") }
    }

    private fun assign(args: List<String>): Int {
        if (args.size < 2) { err.println("assign SHIP BEHAVIOUR [--param value ...]"); err.println(Behaviours.usage()); return 1 }
        val ship = args[0].uppercase()
        val behaviour = args[1]
        val params = mutableMapOf<String, String>()
        var i = 2
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--") && i + 1 < args.size) { params[a.removePrefix("--")] = args[i + 1]; i += 2 } else { err.println("Bad parameter '$a'"); return 1 }
        }
        val plan = Plan.load(planFile()).with(Assignment(ship, behaviour, params))
        val problems = plan.validate(engine.snapshot)
        if (problems.isNotEmpty()) { problems.forEach { err.println(it) }; return 1 }
        Plan.save(planFile(), plan)
        out.println("$ship: $behaviour ${params.entries.joinToString(" ") { (k, v) -> "--$k $v" }}".trimEnd())
        return 0
    }

    /** The dashboard's summary screen as text: phase progress, fleet, spending, revenue, market health. */
    private suspend fun summary() {
        // A one-shot process has not read the site yet; one request gives the progress panel its bill.
        val bill = engine.snapshot.let { s ->
            s.constructionBill ?: s.hqSystem?.let { h -> s.waypointsIn(h).firstOrNull { it.isUnderConstruction } }?.let { site -> runCatching { engine.verbs().construction(site.symbol).materials }.getOrNull() }
        }
        val snap = engine.snapshot.copy(constructionBill = bill)
        val now = engine.clock.now()
        val trend = behaviour.decisions.CreditsTrend.trend(snap.creditsHistory, now)
        val progress = behaviour.decisions.Summary.progress(snap, now, trend)
        out.println(progress.headline)
        progress.lines.forEach { out.println("  [${it.tone.name.lowercase()}] ${it.text}") }
        out.println()
        table(
            listOf("ship", "type", "behaviour", "phase", "where", "cargo", "detail"),
            behaviour.decisions.Summary.fleet(snap, now).map { listOf(it.ship, it.type, it.behaviour, it.phase, it.where, it.cargo, it.detail.take(70)) },
        )
        out.println()
        val spending = behaviour.decisions.Summary.spending(snap)
        val revenue = behaviour.decisions.Summary.revenue(snap)
        table(listOf("spent on", "credits", "share"), spending.map { listOf(it.category, Intentions.format(it.credits), "${(it.share * 100).toInt()}%") })
        out.println()
        table(listOf("earned from", "credits", "share"), revenue.map { listOf(it.category, Intentions.format(it.credits), "${(it.share * 100).toInt()}%") })
        out.println()
        table(
            listOf("system", "markets", "listings", "health", "restricted exports", "scarce", "buried imports", "oldest read"),
            behaviour.decisions.Summary.marketHealth(snap, now).map { h ->
                listOf(h.system, h.markets.toString(), h.listings.toString(), "${(h.score * 100).toInt()}%", h.restrictedExports.toString(), h.scarce.toString(), h.saturatedImports.toString(), h.oldestReadHours?.let { "%.1f h".format(it) } ?: "-")
            },
        )
    }

    /** `phase` shows the plan's phase; `phase escape|boom|late` sets it (the running supervisor picks it up on restart). */
    private fun phase(args: List<String>): Int {
        val file = planFile()
        val current = Plan.load(file)
        val wanted = args.firstOrNull()?.uppercase()
        if (wanted == null) {
            out.println("${current.phase}: ${knowledge.Strategy.describe(current.phase)}")
            out.println("margin floor ${(knowledge.Strategy.marginFloor(current.phase) * 100).toInt()}%; ${if (current.phase == plan.Phase.ESCAPE) "mining sells where the buyer is starved" else "mining sells by price, skipping saturated buyers"}")
            return 0
        }
        val phase = runCatching { plan.Phase.valueOf(wanted) }.getOrNull() ?: run { err.println("phase escape|boom|late"); return 1 }
        Plan.save(file, current.withPhase(phase))
        out.println("phase $phase: ${knowledge.Strategy.describe(phase)}")
        return 0
    }

    /**
     * `race [AGENT ...]`: every agent's bank over time, fleet, gate progress and phase, from their
     * stores, so a late-strategy agent and one that ran the phased plan from the start can be compared.
     */
    private suspend fun race(args: List<String>) {
        val agents = args.map { it.uppercase() }.ifEmpty { Layout.listAgents() }
        val rows = mutableListOf<List<String>>()
        for (symbol in agents) {
            val db = Layout.latestDatabase(symbol) ?: continue
            AgentStore.open(Layout.agentDir(symbol), symbol, Layout.resetDateOf(db)).use { store ->
                val agent = store.getAgent() ?: return@use
                val credits = store.listCredits(java.time.Instant.EPOCH)
                val first = credits.firstOrNull()
                val last = credits.lastOrNull()
                val hours = if (first != null && last != null) (last.at.toEpochMilli() - first.at.toEpochMilli()) / 3_600_000.0 else 0.0
                val hourAgo = last?.let { l -> credits.lastOrNull { it.at.isBefore(l.at.minusSeconds(3600)) } }
                val ships = store.listShips()
                val hq = agent.headquarters.substringBeforeLast('-')
                val site = store.listWaypoints(hq).firstOrNull { it.isUnderConstruction }
                val delivered = site?.let { s -> store.listSupplies(s.symbol).groupBy { it.good }.map { (g, list) -> "${list.sumOf { it.units }} ${g.name}" }.joinToString(", ") }
                val phase = Plan.load(Layout.planFile(symbol)).phase
                rows += listOf(
                    symbol, hq, phase.name, ships.size.toString(),
                    first?.let { "${Intentions.format(it.credits)} at ${time(it.at)}" } ?: "-", Intentions.format(agent.credits),
                    "%.1f".format(hours), if (hours > 0 && first != null) Intentions.format(((agent.credits - first.credits) / hours).toLong()) else "-",
                    if (hourAgo != null && last != null) Intentions.format(last.credits - hourAgo.credits) else "-",
                    site?.symbol ?: "none", delivered?.ifEmpty { "nothing" } ?: "-",
                )
            }
        }
        table(listOf("agent", "home", "phase", "ships", "bank at first record", "bank now", "hours", "cr/h overall", "last hour", "gate", "we delivered"), rows)
    }

    /** `goal fleet TYPE COUNT [--reserve N]` adds a fleet goal; `goal clear TYPE` removes one. */
    private fun goal(args: List<String>): Int {
        val file = planFile()
        when (args.firstOrNull()) {
            "fleet" -> {
                val type = args.getOrNull(1)?.let { shipType(it) } ?: run { err.println("goal fleet TYPE COUNT [--reserve CREDITS]"); return 1 }
                val count = args.getOrNull(2)?.toIntOrNull() ?: run { err.println("goal fleet TYPE COUNT [--reserve CREDITS]"); return 1 }
                val reserve = option(args, "--reserve")?.toLongOrNull() ?: 100_000
                Plan.save(file, Plan.load(file).withGoal(FleetGoal(type, count, reserve)))
                out.println("fleet goal: $count x $type, keeping $reserve credits")
            }
            "clear" -> {
                val type = args.getOrNull(1)?.let { shipType(it) } ?: run { err.println("goal clear TYPE"); return 1 }
                Plan.save(file, Plan.load(file).withoutGoal(type))
                out.println("fleet goal for $type removed")
            }
            else -> { err.println("goal fleet TYPE COUNT [--reserve CREDITS] | goal clear TYPE"); return 1 }
        }
        return 0
    }

    /**
     * `chain add ID --leg GOOD:FROM>TO ... --ships A,B`, `chain release ID [SHIP]`, `chain auto ID on|off`, `chain` for the ledger.
     * Enrolling records each ship's free-agent rate as the counterfactual the release policy holds fixed.
     */
    private suspend fun chain(args: List<String>): Int {
        val file = planFile()
        val plan = Plan.load(file)
        when (args.firstOrNull()) {
            null, "ledger" -> {
                val store = engine.store ?: return 1
                val now = engine.clock.now()
                if (plan.chains.isEmpty()) { out.println("(no chains)"); return 0 }
                plan.chains.forEach { c ->
                    val ledger = behaviour.decisions.Chains.ledger(c, store.listChainTransactions(c.id), now)
                    val verdict = behaviour.decisions.Chains.verdict(ledger, now, c.lastRelease)
                    out.println("chain ${c.id}: ships ${c.ships.joinToString(",")}; enrolled ${time(c.enrolled)}; ${if (c.hold) "held" else "auto"}; reserve ${Intentions.format(c.reserve)}; ${c.note}".trimEnd())
                    table(
                        listOf("leg", "bought", "spent", "sold", "earned", "net"),
                        ledger.legs.map { l -> listOf("${l.good} ${l.from} -> ${l.to}", l.bought.toString(), l.spent.toString(), l.sold.toString(), l.earned.toString(), l.net.toString()) } +
                            listOf(listOf("fuel", "", ledger.fuel.toString(), "", "", (-ledger.fuel).toString())),
                    )
                    out.println("net ${ledger.net} over ${"%.1f".format(ledger.hoursObserved)} h: ${"%.0f".format(ledger.rawPerHour)}/h raw, ${"%.0f".format(ledger.smoothedPerHour)}/h smoothed; team free-agent baseline ${"%.0f".format(verdict.alternativePerHour)}/h")
                    out.println("verdict: ${if (verdict.keep) "KEEP" else "RELEASE ${verdict.releaseShip}"}: ${verdict.reason}")
                    out.println()
                }
            }
            "add" -> {
                val id = args.getOrNull(1) ?: run { err.println("chain add ID --leg GOOD:FROM>TO [--leg ...] --ships A,B [--note text]"); return 1 }
                val legs = args.withIndex().filter { it.value == "--leg" }.mapNotNull { (i, _) -> args.getOrNull(i + 1) }.map { spec ->
                    val good = runCatching { model.market.TradeSymbol.valueOf(spec.substringBefore(':').uppercase()) }.getOrNull() ?: run { err.println("bad leg '$spec'"); return 1 }
                    // cmd.exe swallows '>' even inside an argument, so '/' and '..' are accepted too.
                    val route = spec.substringAfter(':')
                    val parts = route.split(">", "/", "..").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
                    if (parts.size != 2 || parts[0] == parts[1]) { err.println("bad leg '$spec': want GOOD:FROM/TO with two different markets"); return 1 }
                    Leg(good, parts[0], parts[1])
                }
                val ships = option(args, "--ships")?.split(',')?.map { it.trim().uppercase() } ?: emptyList()
                if (legs.isEmpty() || ships.isEmpty()) { err.println("chain add needs at least one --leg and --ships"); return 1 }
                val snap = engine.snapshot
                val now = engine.clock.now()
                val baselines = ships.associateWith { s -> snap.ships[s]?.let { ship -> behaviour.decisions.Trading.rank(snap, ship, now).firstOrNull()?.creditsPerHour } ?: 0.0 }
                val chain = Chain(id, legs, ships, baselines, now.toString(), hold = true, note = option(args, "--note") ?: "", reserve = option(args, "--reserve")?.toLongOrNull() ?: 200_000)
                var next = plan.withChain(chain)
                ships.forEach { next = next.with(Assignment(it, "feed", mapOf("chain" to id))) }
                val problems = next.validate(snap)
                if (problems.isNotEmpty()) { problems.forEach { err.println(it) }; return 1 }
                Plan.save(file, next)
                out.println("chain $id: ${legs.joinToString(", ")}; team ${ships.joinToString(",")}; baselines " + baselines.entries.joinToString(", ") { "${it.key} ${"%.0f".format(it.value)}/h" })
            }
            "release" -> {
                val id = args.getOrNull(1) ?: run { err.println("chain release ID [SHIP]"); return 1 }
                val chain = plan.chain(id) ?: run { err.println("no chain $id"); return 1 }
                val ship = args.getOrNull(2)?.uppercase()
                val releasing = if (ship != null) listOf(ship) else chain.ships
                var next = plan
                releasing.forEach { s -> next = next.with(Assignment(s, behaviour.Behaviours.defaultFor(engine.snapshot.ships[s] ?: return 1) ?: "trade")) }
                val remaining = chain.ships - releasing.toSet()
                next = if (remaining.isEmpty()) next.withoutChain(id) else next.withChain(chain.copy(ships = remaining, lastReleaseAt = engine.clock.now().toString()))
                Plan.save(file, next)
                out.println("released ${releasing.joinToString(",")} from $id" + (if (remaining.isEmpty()) "; chain dissolved" else "; ${remaining.size} still working it"))
            }
            "auto" -> {
                val id = args.getOrNull(1) ?: run { err.println("chain auto ID on|off"); return 1 }
                val chain = plan.chain(id) ?: run { err.println("no chain $id"); return 1 }
                Plan.save(file, plan.withChain(chain.copy(hold = args.getOrNull(2) != "on")))
                out.println("chain $id: release policy ${if (args.getOrNull(2) == "on") "automatic" else "advisory (held)"}")
            }
            else -> { err.println("chain [ledger] | chain add ID --leg GOOD:FROM/TO --ships A,B | chain release ID [SHIP] | chain auto ID on|off"); return 1 }
        }
        return 0
    }

    private fun unassign(args: List<String>): Int {
        val ship = args.firstOrNull()?.uppercase() ?: run { err.println("unassign SHIP"); return 1 }
        Plan.save(planFile(), Plan.load(planFile()).without(ship))
        out.println("$ship: unassigned")
        return 0
    }

    /** Runs the plan for a while, printing every phase change and a summary at the end. */
    private suspend fun runPlan(args: List<String>): Int {
        val duration = option(args, "--for")?.let { parseDuration(it) ?: run { err.println("Bad duration '$it'; try 30m, 2h or 1h30m"); return 1 } } ?: 1.hours
        var plan = Plan.load(planFile())
        if (plan.assignments.isEmpty()) {
            plan = SimRun.defaultPlan(engine.snapshot.ships.values)
            err.println("No plan.json; using the default: " + plan.assignments.joinToString(", ") { "${it.ship} ${it.behaviour}" })
        }
        engine.awaitSystem(engine.snapshot.hqSystem ?: return 1)
        // One driver per agent: two supervisors would fight over the ships and the request budget.
        val lockFile = Layout.runLockFile(engine.snapshot.agent?.symbol ?: return 1)
        var lease = when (val outcome = RunLock.acquire(lockFile)) {
            is RunLock.Outcome.Busy -> {
                err.println("Another run (process ${outcome.lease.pid}, started ${outcome.lease.startedAt}, heartbeat ${outcome.lease.heartbeatAt}) is driving this agent. Stop it first, or wait ${RunLock.STALE_AFTER.seconds}s after it dies.")
                return 3
            }
            is RunLock.Outcome.Held -> outcome.lease
        }
        val shutdownHook = Thread { RunLock.release(lockFile) }
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        val heartbeat = engine.scope.launch {
            while (true) {
                delay(RunLock.HEARTBEAT_EVERY.toMillis())
                lease = RunLock.heartbeat(lockFile, lease) ?: run {
                    err.println("${time(engine.clock.now())} lost the run lease to another process; stopping")
                    return@launch
                }
            }
        }
        val supervisor = Supervisor(engine.scope, engine.verbs(), engine.clock, engine::emit, savePlan = { Plan.save(planFile(), it) })
        val problems = supervisor.apply(plan)
        if (problems.isNotEmpty()) { problems.forEach { err.println(it) }; heartbeat.cancel(); RunLock.release(lockFile); return 1 }
        val startCredits = engine.snapshot.agent?.credits ?: 0
        val startRequests = engine.apiClient?.stats?.requests?.get() ?: 0
        var extracted = 0
        var sold = 0L
        val printer: Job = engine.scope.launch {
            engine.events.collect { e ->
                when (e) {
                    is Event.PhaseChanged -> err.println("${time(engine.clock.now())} ${e.ship} ${e.behaviour}: ${e.phase} ${e.detail}".trimEnd())
                    is Event.Extracted -> { extracted += e.units; err.println("${time(engine.clock.now())} ${e.ship} extracted ${e.units} ${e.good} at ${e.waypoint} (${e.cargo})") }
                    is Event.Sold -> { sold += e.credits; err.println("${time(engine.clock.now())} ${e.ship} sold ${e.units} ${e.good} at ${e.waypoint} for ${e.credits}") }
                    is Event.Bought -> err.println("${time(engine.clock.now())} ${e.ship} bought ${e.units} ${e.good} at ${e.waypoint} for ${e.credits}")
                    is Event.Refueled -> err.println("${time(engine.clock.now())} ${e.ship} refueled at ${e.waypoint}: ${e.units} units for ${e.credits}")
                    is Event.Surveyed -> err.println("${time(engine.clock.now())} ${e.ship} surveyed ${e.waypoint}: ${e.surveys} surveys")
                    is Event.BehaviourFailed -> err.println("${time(engine.clock.now())} ${e.ship} ${e.behaviour} FAILED: ${e.reason}; restart in ${e.restartIn}")
                    is Event.BehaviourFinished -> err.println("${time(engine.clock.now())} ${e.ship} ${e.behaviour} finished")
                    is Event.ShipPurchased -> err.println("${time(engine.clock.now())} bought ${e.ship} (${e.type}) for ${e.credits}")
                    is Event.ContractOffered -> err.println("${time(engine.clock.now())} contract ${e.id.takeLast(6)} offered: ${e.type} paying ${e.payment}")
                    is Event.Delivered -> err.println("${time(engine.clock.now())} ${e.ship} delivered ${e.units} ${e.good} for contract ${e.contract.takeLast(6)}")
                    is Event.ContractFulfilled -> err.println("${time(engine.clock.now())} contract ${e.id.takeLast(6)} fulfilled: +${e.credits}")
                    is Event.Supplied -> err.println("${time(engine.clock.now())} ${e.ship} supplied ${e.units} ${e.good} to ${e.site}; ${e.remaining} to go")
                    is Event.Jumped -> err.println("${time(engine.clock.now())} ${e.ship} jumped to ${e.waypoint} (antimatter ${e.antimatterCost})")
                    is Event.PhaseAdvanced -> err.println("${time(engine.clock.now())} PHASE ${e.phase}: ${e.description}")
                    is Event.Charted -> err.println("${time(engine.clock.now())} ${e.ship} charted ${e.waypoint}: +${e.credits}")
                    is Event.Warning -> err.println("${time(engine.clock.now())} warning: ${e.message}")
                    is Event.Failure -> err.println("${time(engine.clock.now())} failure: ${e.message}")
                    else -> Unit
                }
            }
        }
        err.println("Running ${plan.assignments.size} assignment(s) for $duration as process ${lease.pid}; Ctrl+C stops early.")
        try {
            withTimeoutOrNull(duration) { while (supervisor.active.isNotEmpty() && heartbeat.isActive) engine.clock.sleep(kotlin.time.Duration.parse("5s")) }
        } finally {
            supervisor.stopAll()
            heartbeat.cancel()
            RunLock.release(lockFile)
            runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }
        printer.cancel()
        val endCredits = engine.snapshot.agent?.credits ?: 0
        table(
            listOf("field", "value"),
            listOf(
                listOf("ran for", duration.toString()),
                listOf("credits", "$startCredits -> $endCredits (${endCredits - startCredits})"),
                listOf("sold for", sold.toString()),
                listOf("units extracted", extracted.toString()),
                listOf("requests", ((engine.apiClient?.stats?.requests?.get() ?: 0) - startRequests).toString()),
                listOf("finished", supervisor.finished.joinToString(",").ifEmpty { "-" }),
            ),
        )
        return 0
    }

    private suspend fun buy(args: List<String>): Int {
        if (args.size < 2) { err.println("buy SHIP_TYPE SHIPYARD"); return 1 }
        val type = shipType(args[0]) ?: run { err.println("Unknown ship type ${args[0]}; one of ${ShipType.entries.joinToString(",")}"); return 1 }
        val ship = engine.verbs().purchaseShip(type, args[1].uppercase())
        engine.refreshShips()
        out.println("${ship.symbol} ${ship.registration.role} at ${ship.nav.waypointSymbol}; credits now ${engine.snapshot.agent?.credits}")
        return 0
    }

    // The simulator on virtual time: no engine, no network

    private suspend fun simulate(args: List<String>): Int {
        val hours = option(args, "--hours")?.toIntOrNull() ?: 24
        val seedFile = option(args, "--seed")?.let(::File)
        val seed = try {
            loadSeed(seedFile)
        } catch (e: Exception) {
            err.println("Cannot seed the simulator: ${e.message}. Boot once against the live API (any command) or pass --seed FILE."); return 2
        }
        val planFile = option(args, "--plan")?.let(::File) ?: Layout.planFile(seed.agent.symbol)
        var plan = Plan.load(planFile)
        if (plan.assignments.isEmpty()) plan = SimRun.defaultPlan(seed.ships)
        val rules = SimRules()
        val purchases = option(args, "--buy")?.split(',')?.map { shipType(it) ?: run { err.println("Unknown ship type $it"); return 1 } } ?: emptyList()
        err.println("Simulating ${seed.systemSymbol} for ${hours}h with ${plan.assignments.size} assignment(s): " + plan.assignments.joinToString(", ") { "${it.ship} ${it.describe()}" } +
            (if (purchases.isEmpty()) "" else "; buying ${purchases.joinToString(",")} first"))
        val report = try {
            SimRun(seed, plan, hours, rules, randomSeed = option(args, "--random")?.toLongOrNull() ?: 1, purchases = purchases).run()
        } catch (e: IllegalArgumentException) {
            err.println(e.message); return 1
        } catch (e: IllegalStateException) {
            err.println(e.message); return 1
        }
        printReport(report, args.contains("--trace"))
        return 0
    }

    private suspend fun loadSeed(seedFile: File?): SimSeed {
        if (seedFile != null) return SimSeed.load(seedFile)
        val symbol = (agentOption ?: model.loadProfile().name).uppercase()
        val db = Layout.latestDatabase(symbol) ?: error("no database under ${Layout.agentDir(symbol).path}")
        return AgentStore.open(Layout.agentDir(symbol), symbol, Layout.resetDateOf(db)).use { SimSeed.fromStore(it) }
    }

    private fun printReport(r: SimReport, trace: Boolean) {
        table(
            listOf("field", "value"),
            listOf(
                listOf("hours", r.hours.toString()),
                listOf("credits", "${r.startingCredits} -> ${r.endingCredits} (${if (r.earned >= 0) "+" else ""}${r.earned})"),
                listOf("credits/hour", "%.0f".format(r.perHour)),
                listOf("extractions", "${r.extractions} (${r.unitsExtracted} units)"),
                listOf("units sold", r.unitsSold.toString()),
                listOf("fuel spent", r.fuelSpent.toString()),
                listOf("goods bought", r.goodsBought.toString()),
                listOf("API calls", "${r.calls} (%.0f/hour, budget 7200/hour)".format(r.callsPerHour)),
                listOf("failures", r.failures.size.toString()),
            ),
        )
        out.println()
        table(listOf("hour", "credits"), r.creditsByHour.mapIndexed { i, c -> listOf((i + 1).toString(), c.toString()) })
        out.println()
        table(listOf("good", "units", "credits", "avg"), r.salesByGood.entries.sortedByDescending { it.value.second }.map { (g, v) -> listOf(g, v.first.toString(), v.second.toString(), (v.second / v.first.coerceAtLeast(1)).toString()) })
        out.println()
        table(listOf("asteroid", "state"), r.asteroids.entries.sortedBy { it.key }.map { listOf(it.key, it.value) })
        out.println()
        table(listOf("ship", "final status"), r.finalStatus.entries.sortedBy { it.key }.map { listOf(it.key, it.value) })
        r.failures.take(10).forEach { out.println("failure: $it") }
        if (trace) {
            out.println()
            r.trace.phases.forEach { (ship, s) -> out.println("${time(s.since)} $ship ${s.behaviour}: ${s.phase} ${s.detail}".trimEnd()) }
        }
    }

    // Helpers

    private fun shipType(text: String): ShipType? =
        runCatching { ShipType.valueOf(text.uppercase().let { if (it.startsWith("SHIP_")) it else "SHIP_$it" }) }.getOrNull()

    private fun option(args: List<String>, name: String): String? = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    private fun parseDuration(text: String): Duration? =
        runCatching { Duration.parse(text.replace(Regex("(?<=[hms])(?=\\d)"), " ")) }.getOrNull()

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

    private fun time(instant: Instant): String = TIME.format(instant.atZone(ZoneId.systemDefault()))

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
            Usage: tradey [--agent SYMBOL] [--refresh] [--sim[=FACTOR]] <command> [args]

              status                     agent, fleet size, reset date, request count
              agent                      the agent record
              ships                      the fleet, with what each ship's behaviour is doing
              waypoints [SYSTEM]         waypoints of a system (default: home)
              markets [SYSTEM]           markets of a system with imports, exports and when prices were read
              market WAYPOINT            prices at one market (needs a ship there for prices)
              shipyards [SYSTEM]         shipyards of a system and what they sell
              asteroids [SYSTEM] [--ship S]   asteroids ranked by credits per hour for a mining ship
              trades [--ship S] [--all]  buy-here-sell-there routes ranked by credits per hour
              intentions                 what the bot is doing and saving for, and the credits trend
              contracts                  every contract seen with its payment, our cost and the dates
              summary                    the dashboard's summary as text: phase progress, fleet, spending, revenue, market health
              phase [escape|boom|late]   show or set the plan's phase (docs/phases.md): which weights and default jobs apply
              race [AGENT ...]           every agent's bank over time, fleet, gate progress and phase, side by side
              gate [SITE]                the construction bill, what we delivered and spent, and the cost to finish
              jumpgate [GATE]            a gate's connections
              jump SHIP GATE             jump a ship through the gate it is at to a connected gate (buys antimatter)
              register SYMBOL FACTION    register a new agent on this account (needs profile/accounttoken.secret)
              catalog [ships|parts]      every ship listing and part for sale seen by any agent on this account; no network
              extractions                every extraction made this reset
              plan                       the plan: which ship runs which behaviour
              assign SHIP BEHAVIOUR [--param value ...]   add or replace an assignment (see 'behaviours')
              unassign SHIP              remove an assignment
              goal fleet TYPE N [--reserve C]   buy up to N of TYPE when a trader docks at a yard and C credits stay in the bank
              goal clear TYPE            drop that fleet goal
              chain                      the chains' ledgers and the release policy's verdicts
              chain add ID --leg GOOD:FROM/TO ... --ships A,B [--reserve C]   enrol a team on a chain (records their free-agent rates)
              chain release ID [SHIP]    put a ship (or the team) back on its default behaviour
              run [--for 2h]             run the plan, printing phases, then summarise
              buy TYPE SHIPYARD          buy a ship (a ship of yours must be at the shipyard)
              sim [--hours 24] [--buy TYPE,..] [--seed FILE] [--plan FILE] [--random N] [--trace]
                                         run the plan against the simulator on virtual time; no network.
                                         --buy purchases ships first and puts them to work, to price an expansion
              behaviours                 list behaviours and their parameters
              repl                       read commands from standard input until EOF

            --agent picks an agent folder under profile/agents; --refresh re-fetches instead of using cached data.
            --sim runs every command against a simulated copy of the agent's system at FACTOR times real speed (default 60), without the network.
            With no arguments the terminal dashboard starts instead.
            """.trimIndent()
        )
    }

    companion object {
        val COMMANDS = listOf(
            "status", "agent", "ships", "waypoints", "markets", "market", "shipyards", "asteroids", "trades", "intentions", "contracts", "gate", "jumpgate", "jump", "register", "catalog", "race", "summary", "extractions",
            "plan", "assign", "unassign", "goal", "chain", "phase", "run", "buy", "sim", "repl",
        )
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val PART_PREFIXES = listOf("MOUNT_", "MODULE_", "ENGINE_", "REACTOR_")

        /** An engine over the simulator, seeded from the agent's store, with a throwaway store of its own. */
        fun simEngine(options: SimOptions): Engine {
            val symbol = (options.agent ?: model.loadProfile().name).uppercase()
            val db = Layout.latestDatabase(symbol) ?: error("no database under ${Layout.agentDir(symbol).path}; boot once against the live API first")
            val seed = runBlocking { AgentStore.open(Layout.agentDir(symbol), symbol, Layout.resetDateOf(db)).use { SimSeed.fromStore(it) } }
            val clock = AcceleratedClock(options.factor)
            val universe = SimUniverse(seed, clock)
            val server = FakeServer(universe)
            val storeDir = Files.createTempDirectory("tradey-sim").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("sim-engine"))
            val pacer = RequestPacer(scope)
            return Engine(scope, pacer, clock, apiFactory = { SpaceTradersApi(ApiClient("sim", pacer, server.engine)) }, storeFactory = { s, reset -> AgentStore.open(storeDir, s, reset) })
        }
    }
}

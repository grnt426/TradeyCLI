package bridge

import app.App
import bridge.canvas.DotCanvas
import bridge.canvas.FrameDiff
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Surface
import bridge.scene.FeedLine
import bridge.tty.Input
import bridge.tty.MordantTty
import bridge.tty.Size
import bridge.tty.Tty
import bridge.views.DiagnosticsView
import bridge.views.EconomyView
import bridge.views.GalaxyView
import bridge.views.HomeView
import bridge.views.MarketsView
import bridge.views.ShipView
import bridge.views.SpikeView
import bridge.views.SystemView
import bridge.views.WaypointView
import cli.LineMode
import engine.Engine
import engine.Snapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import model.BootProgress
import model.exceptions.BootFailure
import startup.BootManager
import storage.PriceObservation
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/** What the views read: the engine's latest snapshot plus the console's own state. */
class BridgeModel(private val engine: Engine, val mode: String) {
    fun snapshot(): Snapshot = engine.state.value
    fun now(): Instant = engine.clock.now()

    /** The screen's current size, for views that need to know where the bottom is. */
    var width: Int = 0
    var height: Int = 0

    /** What the screens agree is selected: picking a ship on one screen shows it on the others. */
    var selectedWaypoint: String? = null
    var selectedShip: String? = null

    /** A system chosen on the galaxy screen for the system screen to open; consumed when it does. */
    var selectedSystem: String? = null

    /** Server status, leaderboards, public agents, and the wider galaxy, fetched in the background. */
    val galaxy = Galaxy(engine)

    /** A view asks for another view by name; the shell switches on the next frame. */
    var pendingView: String? = null
    fun navigateTo(name: String) { pendingView = name }

    /** One agent of the account in the race table. */
    data class RaceRow(val agent: String, val phase: String, val ships: Int, val bank: Long, val perHour: Long?, val lastHour: Long?, val gate: String)

    private var allCreditsAt = 0L
    @Volatile private var allCreditsCache: List<behaviour.decisions.CreditPoint> = emptyList()

    /** The bank since the reset began, from the store, refreshed in the background every two minutes. */
    fun allCredits(): List<behaviour.decisions.CreditPoint> {
        val nowNanos = System.nanoTime()
        if (nowNanos - allCreditsAt > 120_000_000_000L) {
            allCreditsAt = nowNanos
            val store = engine.store
            if (store != null) engine.scope.launch {
                runCatching { store.listCredits(Instant.EPOCH) }.onSuccess { allCreditsCache = it }
            }
        }
        return allCreditsCache.ifEmpty { snapshot().creditsHistory }
    }

    private var contractsAt = 0L
    @Volatile private var contractsCache: List<storage.ContractRecord> = emptyList()

    /** Every contract this reset with what it cost us, from the store, refreshed every two minutes. */
    fun contractRecords(): List<storage.ContractRecord> {
        val nowNanos = System.nanoTime()
        if (nowNanos - contractsAt > 120_000_000_000L) {
            contractsAt = nowNanos
            val store = engine.store
            if (store != null) engine.scope.launch {
                runCatching { store.listContractRecords() }.onSuccess { contractsCache = it }
            }
        }
        return contractsCache
    }

    private var raceAt = 0L
    @Volatile private var raceCache: List<RaceRow> = emptyList()

    /**
     * Every agent of the account: ours from the snapshot, the others from their stores on disk,
     * refreshed in the background every five minutes.
     */
    fun race(): List<RaceRow> {
        val nowNanos = System.nanoTime()
        if (nowNanos - raceAt > 300_000_000_000L) {
            raceAt = nowNanos
            engine.scope.launch { runCatching { raceCache = computeRace() }.onFailure { logger.warn(it) { "race failed" } } }
        }
        return raceCache
    }

    private suspend fun computeRace(): List<RaceRow> {
        val ours = snapshot().agent?.symbol
        val rows = ArrayList<RaceRow>()
        for (symbol in storage.Layout.listAgents()) {
            if (symbol == ours) {
                val snap = snapshot()
                val site = snap.hqSystem?.let { h -> snap.waypointsIn(h).firstOrNull { it.isUnderConstruction } }
                val gate = when {
                    site == null -> "none"
                    snap.constructionBill != null -> "${snap.constructionBill!!.sumOf { it.fulfilled }}/${snap.constructionBill!!.sumOf { it.required }} at ${site.symbol.substringAfterLast('-')}"
                    else -> site.symbol.substringAfterLast('-')
                }
                rows += raceRow(symbol, snap.plan?.phase?.name ?: "?", snap.ships.size, snap.agent?.credits ?: 0, allCredits(), gate)
                continue
            }
            val db = storage.Layout.latestDatabase(symbol) ?: continue
            storage.AgentStore.open(storage.Layout.agentDir(symbol), symbol, storage.Layout.resetDateOf(db)).use { store ->
                val agent = store.getAgent() ?: return@use
                val credits = store.listCredits(Instant.EPOCH)
                val hq = agent.headquarters.substringBeforeLast('-')
                val site = store.listWaypoints(hq).firstOrNull { it.isUnderConstruction }
                val delivered = site?.let { s -> store.listSupplies(s.symbol).sumOf { it.units } }
                val phase = runCatching { plan.Plan.load(storage.Layout.planFile(symbol)).phase.name }.getOrDefault("?")
                rows += raceRow(symbol, phase, store.listShips().size, agent.credits, credits, site?.let { "${delivered ?: 0} delivered at ${it.symbol.substringAfterLast('-')}" } ?: "none")
            }
        }
        return rows.sortedByDescending { it.bank }
    }

    private fun raceRow(symbol: String, phase: String, ships: Int, bank: Long, credits: List<behaviour.decisions.CreditPoint>, gate: String): RaceRow {
        val first = credits.firstOrNull()
        val last = credits.lastOrNull()
        val hours = if (first != null && last != null) (last.at.toEpochMilli() - first.at.toEpochMilli()) / 3_600_000.0 else 0.0
        val hourAgo = last?.let { l -> credits.lastOrNull { it.at.isBefore(l.at.minusSeconds(3600)) } }
        return RaceRow(
            symbol, phase, ships, bank,
            if (hours > 0.1 && first != null) ((bank - first.credits) / hours).toLong() else null,
            if (hourAgo != null && last != null) last.credits - hourAgo.credits else null,
            gate,
        )
    }

    private class CachedPrices(val fetchedAt: Long, val observations: List<PriceObservation>)
    private val priceCache = java.util.concurrent.ConcurrentHashMap<String, CachedPrices>()

    /**
     * The last day of price reads at [market] from the store, refreshed in the background at most
     * once a minute; empty until the first read lands.
     */
    fun prices(market: String): List<PriceObservation> {
        val cached = priceCache[market]
        val nowNanos = System.nanoTime()
        if (cached == null || nowNanos - cached.fetchedAt > 60_000_000_000L) {
            priceCache[market] = CachedPrices(nowNanos, cached?.observations ?: emptyList())
            val store = engine.store
            if (store != null) engine.scope.launch {
                runCatching { store.listPrices(market, now().minus(java.time.Duration.ofHours(24))) }
                    .onSuccess { priceCache[market] = CachedPrices(System.nanoTime(), it) }
                    .onFailure { logger.warn(it) { "reading prices for $market failed" } }
            }
        }
        return cached?.observations ?: emptyList()
    }

    private val feedLines = ArrayDeque<FeedLine>()

    /** Every engine event so far, oldest first, at most [FEED_LIMIT]. */
    fun feed(): List<FeedLine> = synchronized(feedLines) { feedLines.toList() }

    /** A failure or warning the engine reported, kept for the diagnostics screen. */
    class Problem(val at: Instant, val text: String, val severe: Boolean)
    private val problemList = ArrayDeque<Problem>()
    fun problems(): List<Problem> = synchronized(problemList) { problemList.toList() }

    /** Turns the engine's events into feed lines for as long as the engine runs; failures are kept apart as well. */
    fun followEvents() {
        engine.scope.launch {
            engine.events.collect { e ->
                val (text, tone) = EventLines.describe(e)
                synchronized(feedLines) {
                    feedLines.addLast(FeedLine(now(), text, tone))
                    while (feedLines.size > FEED_LIMIT) feedLines.removeFirst()
                }
                val severe = e is engine.Event.Failure || e is engine.Event.BehaviourFailed
                if (severe || e is engine.Event.Warning) synchronized(problemList) {
                    problemList.addLast(Problem(now(), text, severe))
                    while (problemList.size > 200) problemList.removeFirst()
                }
            }
        }
    }

    fun apiStats(): api.ApiStats = engine.apiClient?.stats ?: api.ApiStats()
    fun pacer(): api.RequestPacer = engine.pacer

    private var requestLogAt = 0L
    @Volatile private var requestLogCache: List<storage.RequestLogEntry> = emptyList()

    /** The last hour of the request log, every process of this agent included, refreshed every fifteen seconds. */
    fun requestLog(): List<storage.RequestLogEntry> {
        val nowNanos = System.nanoTime()
        if (nowNanos - requestLogAt > 15_000_000_000L) {
            requestLogAt = nowNanos
            val store = engine.store
            if (store != null) engine.scope.launch {
                runCatching { store.listRequests(now().minus(java.time.Duration.ofHours(1))) }.onSuccess { requestLogCache = it }
            }
        }
        return requestLogCache
    }

    private var logTailAt = 0L
    @Volatile private var logTailCache: List<String> = emptyList()

    /** WARN and ERROR lines from the end of log.txt, newest first, re-read every five seconds. */
    fun logTail(): List<String> {
        val nowNanos = System.nanoTime()
        if (nowNanos - logTailAt > 5_000_000_000L) {
            logTailAt = nowNanos
            engine.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val file = java.io.File("log.txt")
                    if (!file.isFile) return@runCatching emptyList<String>()
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        val len = raf.length()
                        val from = (len - 256_000).coerceAtLeast(0)
                        raf.seek(from)
                        val bytes = ByteArray((len - from).toInt())
                        raf.readFully(bytes)
                        String(bytes, Charsets.UTF_8).lines()
                            .filter { " WARN " in it || " ERROR " in it }
                            .map { it.replace(Regex("^(\\S+ \\S+),\\d+ (WARN|ERROR) \\S+ \\[[^\\]]*\\] "), "$1 $2 ") }
                            .takeLast(60)
                            .reversed()
                    }
                }.onSuccess { logTailCache = it }
            }
        }
        return logTailCache
    }

    val recentInputs = ArrayDeque<Input>()
    var lastClick: Pair<Int, Int>? = null
    var fps: Double = 0.0
    var lastFrameBytes: Int = 0
    var renderMillis: Double = 0.0
    var ttyDescription: String = ""
    var dots: DotCanvas.Mode = DotCanvas.Mode.BRAILLE

    fun record(input: Input) {
        recentInputs.addLast(input)
        while (recentInputs.size > 40) recentInputs.removeFirst()
        if (input is Input.Mouse && input.isPress) lastClick = input.x to input.y
    }

    private companion object {
        const val FEED_LIMIT = 500
    }
}

/**
 * The new console: `TradeyCLI bridge [--agent SYMBOL] [--sim[=FACTOR]] [--no-boot] [--fps N]
 * [--view NAME] [--select SYMBOL] [--frame [--size WxH] [--ansi] [--wait]] [--bench FRAMES]`. Without `--frame` or
 * `--bench` it takes the terminal and runs until `q`, Escape or Ctrl+C; `--frame` prints one frame to
 * stdout and exits, which is how the look is checked from a shell with no TTY; `--bench` renders
 * frames headless and reports what each would cost the terminal.
 */
object Bridge {
    private const val DEFAULT_FPS = 20

    fun run(args: List<String>): Int {
        var agent: String? = null
        var sim: LineMode.SimOptions? = null
        var boot = true
        var frame = false
        var ansi = false
        var wait = false
        var size = Size(160, 45)
        var fps = DEFAULT_FPS
        var bench = 0
        var viewName: String? = null
        var select: String? = null
        var settle = 2
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a == "--agent" && i + 1 < args.size -> agent = args[++i]
                a.startsWith("--agent=") -> agent = a.substringAfter('=')
                a == "--sim" -> sim = LineMode.SimOptions(null, 60.0)
                a.startsWith("--sim=") -> sim = LineMode.SimOptions(null, a.substringAfter('=').toDoubleOrNull() ?: 60.0)
                a == "--no-boot" -> boot = false
                a == "--frame" -> frame = true
                a == "--ansi" -> ansi = true
                a == "--wait" -> wait = true
                a == "--size" && i + 1 < args.size -> size = parseSize(args[++i]) ?: return usage("bad --size ${args[i]}")
                a.startsWith("--size=") -> size = parseSize(a.substringAfter('=')) ?: return usage("bad --size")
                a == "--fps" && i + 1 < args.size -> fps = args[++i].toIntOrNull()?.coerceIn(1, 60) ?: return usage("bad --fps")
                a == "--bench" && i + 1 < args.size -> bench = args[++i].toIntOrNull()?.coerceAtLeast(1) ?: return usage("bad --bench")
                a == "--view" && i + 1 < args.size -> viewName = args[++i]
                a == "--select" && i + 1 < args.size -> select = args[++i]
                a == "--settle" && i + 1 < args.size -> settle = args[++i].toIntOrNull()?.coerceIn(0, 60) ?: return usage("bad --settle")
                a == "--help" || a == "-h" -> return usage(null)
                else -> return usage("unknown argument $a")
            }
            i++
        }
        sim = sim?.copy(agent = agent)
        val engine = if (sim == null) App.engine else LineMode.simEngine(sim)
        val mode = if (sim != null) "sim ×${sim.factor}" else if (boot) "live" else "no boot"
        val model = BridgeModel(engine, mode)
        model.followEvents()
        if (boot) startBoot(engine, sim, agent)

        // A ship or waypoint symbol; each screen looks it up in its own table, so one flag serves both.
        select?.let { model.selectedShip = it; model.selectedWaypoint = it }
        val shell = Shell(listOf(HomeView(), SystemView(), WaypointView(), ShipView(), MarketsView(), EconomyView(), GalaxyView(), DiagnosticsView(), SpikeView()), model)
        if (viewName != null && !shell.show(viewName)) return usage("no view '$viewName'; views: ${shell.views.joinToString { it.title }}")
        return when {
            bench > 0 -> bench(shell, model, size, fps, bench, wait)
            frame -> renderOnce(shell, model, size, ansi, wait, settle)
            else -> loop(shell, model, fps)
        }
    }

    private fun usage(problem: String?): Int {
        if (problem != null) System.err.println(problem)
        System.err.println("usage: TradeyCLI bridge [--agent SYMBOL] [--sim[=FACTOR]] [--no-boot] [--fps N] [--view NAME] [--select SYMBOL] [--frame [--size WxH] [--ansi] [--wait [--settle N]]] [--bench FRAMES [--size WxH]]")
        return if (problem == null) 0 else 1
    }

    private fun parseSize(s: String): Size? {
        val (w, h) = s.lowercase().split('x').takeIf { it.size == 2 } ?: return null
        val ww = w.toIntOrNull() ?: return null
        val hh = h.toIntOrNull() ?: return null
        if (ww < 20 || hh < 5) return null
        return Size(ww, hh)
    }

    /** Boots in the background; the screen shows the progress and carries on if it fails. */
    private fun startBoot(engine: Engine, sim: LineMode.SimOptions?, agent: String?) {
        BootProgress.reset(if (sim == null) "Loading agent" else "Loading simulator")
        engine.scope.launch {
            try {
                if (sim == null) BootManager.normalStart(agentSymbol = agent, progress = BootProgress::step)
                else engine.boot("sim", BootProgress::step)
                BootProgress.finish()
                engine.followStore()
            } catch (e: BootFailure) {
                BootProgress.fail(e.message ?: "boot failed")
            } catch (e: Exception) {
                logger.error(e) { "bridge boot failed" }
                BootProgress.fail("${e::class.simpleName}: ${e.message}")
            }
        }
    }

    /** Blocks until the background boot has finished or failed, a minute at most. */
    private fun awaitBoot(model: BridgeModel) {
        runBlocking {
            val deadline = System.nanoTime() + 60.seconds.inWholeNanoseconds
            while (BootProgress.current != null || (model.snapshot().agent == null && BootProgress.failure == null)) {
                if (System.nanoTime() > deadline) break
                kotlinx.coroutines.delay(100)
            }
        }
    }

    private fun renderOnce(shell: Shell, model: BridgeModel, size: Size, ansi: Boolean, wait: Boolean, settle: Int): Int {
        if (wait) awaitBoot(model)
        model.ttyDescription = "headless ${size.width}x${size.height}"
        model.width = size.width
        model.height = size.height
        // The first paint starts the background reads the screens rely on; after boot, give them a moment and paint again.
        if (wait) {
            // `--settle N` waits longer, painting once a second so each paint can start the reads the last one revealed;
            // panels that fill on the store follower's first tick need about five seconds.
            repeat(settle.coerceAtLeast(1)) {
                shell.paint(Painter(Surface(size.width, size.height), Rect(0, 0, size.width, size.height)), 0.0)
                Thread.sleep(1000L)
            }
        }
        val surface = Surface(size.width, size.height)
        shell.paint(Painter(surface, Rect(0, 0, size.width, size.height)), 0.0)
        print(if (ansi) surface.toAnsi() else surface.toText())
        return if (BootProgress.failure != null && wait) 2 else 0
    }

    /** Renders [frames] consecutive frames headless at [fps] and reports what each would have cost the terminal. */
    private fun bench(shell: Shell, model: BridgeModel, size: Size, fps: Int, frames: Int, wait: Boolean): Int {
        if (wait) awaitBoot(model)
        model.ttyDescription = "bench ${size.width}x${size.height}"
        model.width = size.width
        model.height = size.height
        var prev: Surface? = null
        var bytes = 0L
        var maxBytes = 0
        var nanos = 0L
        var first = 0
        for (n in 0 until frames) {
            val t0 = System.nanoTime()
            val next = Surface(size.width, size.height)
            shell.paint(Painter(next, Rect(0, 0, size.width, size.height)), n.toDouble() / fps)
            val out = FrameDiff.render(prev, next)
            nanos += System.nanoTime() - t0
            if (n == 0) first = out.length else { bytes += out.length; maxBytes = maxOf(maxBytes, out.length) }
            prev = next
        }
        val later = (frames - 1).coerceAtLeast(1)
        println("${size.width}x${size.height} at $fps fps: first frame $first B, then avg ${bytes / later} B, max $maxBytes B, render+diff avg %.2f ms".format(nanos / 1e6 / frames))
        return 0
    }

    /**
     * Input is read on its own thread, because a timed wait on Windows rounds up to the 15 ms
     * timer tick and would eat a third of every frame; the render thread sleeps for the exact
     * remainder and spins the last moment.
     */
    private fun loop(shell: Shell, model: BridgeModel, fps: Int): Int {
        val tty: Tty = MordantTty()
        val frameNanos = 1_000_000_000L / fps
        val running = AtomicBoolean(true)
        var failure: Exception? = null
        try {
            tty.enter()
            model.ttyDescription = tty.describe()
            val inputs = ConcurrentLinkedQueue<Input>()
            Thread({
                while (running.get()) {
                    try {
                        tty.poll(200.milliseconds)?.let { inputs.add(it) }
                    } catch (e: Exception) {
                        if (running.get()) logger.warn(e) { "reading input failed" }
                    }
                }
            }, "bridge-input").apply { isDaemon = true; start() }

            var prev: Surface? = null
            var size = tty.size()
            model.width = size.width
            model.height = size.height
            val start = System.nanoTime()
            var framesThisSecond = 0
            var secondStart = start
            while (running.get()) {
                val frameStart = System.nanoTime()
                while (true) {
                    val input = inputs.poll() ?: break
                    model.record(input)
                    if (shell.onInput(input)) { running.set(false); break }
                }
                if (!running.get()) break
                val now = tty.size()
                if (now != size) {
                    size = now
                    prev = null
                }
                model.width = size.width
                model.height = size.height
                val t = (frameStart - start) / 1e9
                val next = Surface(size.width, size.height)
                shell.paint(Painter(next, Rect(0, 0, size.width, size.height)), t)
                val out = FrameDiff.render(prev, next)
                tty.write(out)
                model.lastFrameBytes = out.length
                model.renderMillis = (System.nanoTime() - frameStart) / 1e6
                prev = next
                framesThisSecond++
                val sinceSecond = System.nanoTime() - secondStart
                if (sinceSecond >= 1_000_000_000L) {
                    model.fps = framesThisSecond * 1e9 / sinceSecond
                    framesThisSecond = 0
                    secondStart = System.nanoTime()
                }
                pauseUntil(frameStart + frameNanos)
            }
            return 0
        } catch (e: Exception) {
            logger.error(e) { "bridge failed" }
            failure = e
            return 1
        } finally {
            running.set(false)
            tty.close()
            // Printed after the alternate screen is gone, or leaving it would wipe the message.
            failure?.let { System.err.println("bridge failed: ${it::class.simpleName}: ${it.message}; see log.txt") }
        }
    }

    /** Sleeps to within two milliseconds of [deadlineNanos], then spins: sleep alone overshoots on Windows. */
    private fun pauseUntil(deadlineNanos: Long) {
        val sleepUntil = deadlineNanos - 2_000_000L
        val sleep = sleepUntil - System.nanoTime()
        if (sleep > 0) Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
        while (System.nanoTime() < deadlineNanos) Thread.onSpinWait()
    }
}

package bridge.views

import behaviour.decisions.Summary
import bridge.BridgeModel
import bridge.Format
import bridge.canvas.Attr
import bridge.canvas.Glyphs
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.fx.Starfield
import bridge.glyphs.Palette
import bridge.scene.Line
import bridge.scene.Table
import bridge.scene.TextBlock
import java.time.Duration
import java.time.Instant

/**
 * Where the problems live, so they need not fit anywhere else: the API's rate and errors, the
 * pacer's lanes, behaviours that failed and are waiting to restart, markets nobody has read for
 * hours, ships that look to be on the same order, who holds the run lock, the warnings in the log,
 * and the console's own numbers.
 */
class DiagnosticsView : WidgetView() {
    override val title = "Diagnostics"
    private var model: BridgeModel? = null
    private val stars = Starfield(seed = 29, density = 0.004)

    private val requests = Table(
        columns = listOf(
            Table.Column("age", 5, alignRight = true),
            Table.Column("status", 6, alignRight = true),
            Table.Column("ms", 6, alignRight = true),
            Table.Column("try", 3, alignRight = true),
            Table.Column("lane", 11),
            Table.Column("path"),
        ),
    )
    private val problems = TextBlock(lines = { problemLines() }, empty = "no failures or warnings since the console started")
    private val stale = Table(
        columns = listOf(Table.Column("market", 13), Table.Column("system", 9), Table.Column("last read", 10, alignRight = true), Table.Column("goods", 5, alignRight = true)),
    )
    private val conflicts = TextBlock(lines = { conflictLines() }, empty = "no two ships share an order")
    private val log = TextBlock(lines = { logLines() }, empty = "no warnings or errors in the log's tail")

    override fun paint(p: Painter, model: BridgeModel, t: Double) {
        this.model = model
        beginFrame()
        stars.paint(p, t)
        val snap = model.snapshot()
        val now = model.now()

        val (top, middle, bottom) = Rect(0, 0, p.width, p.height).rows(Len.fixed(11), Len.weight(3), Len.weight(2))
        val (apiRect, runRect, consoleRect) = top.cols(Len.weight(3), Len.weight(2), Len.weight(2))
        api(p.panel(apiRect, "API · last hour"), model)
        run(p.panel(runRect, "Run"), model)
        console(p.panel(consoleRect, "Console"), model)

        val (requestsRect, problemsRect) = middle.cols(Len.weight(3), Len.weight(2))
        val entries = model.requestLog()
        val bad = entries.filter { it.status >= 400 || it.status == 0 }.sortedByDescending { it.at }
        val slow = entries.filter { it.status in 200..399 && it.durationMs >= 2000 }.sortedByDescending { it.durationMs }
        requests.setRows((bad.take(40) + slow.take(20)).map { e ->
            Table.Row(
                "${e.at.toEpochMilli()}:${e.path}",
                listOf(Format.age(e.at, now), if (e.status == 0) "fail" else e.status.toString(), e.durationMs.toString(), e.attempt.toString(), e.priority.lowercase(), e.path),
                when {
                    e.status == 429 -> Palette.warn
                    e.status >= 500 || e.status == 0 -> Palette.bad
                    e.status >= 400 -> Palette.warn
                    else -> Palette.textDim
                },
            )
        })
        place(requests, p.panel(requestsRect, "Failed and slow requests · ${bad.size} failed, ${slow.size} over 2 s", focus === requests, hint = "from request_log"), t)
        place(problems, p.panel(problemsRect, "Failures and warnings"), t)

        val (staleRect, conflictsRect, logRect) = bottom.cols(Len.weight(2), Len.weight(2), Len.weight(3))
        stale.setRows(staleRows(model))
        place(stale, p.panel(staleRect, "Stale markets · unread for 3 h+", focus === stale), t)
        place(conflicts, p.panel(conflictsRect, "Same order"), t)
        place(log, p.panel(logRect, "log.txt · warnings and errors"), t)
        endFrame(listOf(requests, stale))
    }

    /** Requests per minute over the last hour as a sparkline, the counters, and the pacer's lanes. */
    private fun api(p: Painter, model: BridgeModel) {
        val now = model.now()
        val entries = model.requestLog()
        val buckets = IntArray(60)
        val errors = IntArray(60)
        for (e in entries) {
            val minutesAgo = Duration.between(e.at, now).toMinutes().toInt()
            if (minutesAgo in 0..59) {
                buckets[59 - minutesAgo]++
                if (e.status >= 400 || e.status == 0) errors[59 - minutesAgo]++
            }
        }
        val max = buckets.max().coerceAtLeast(1)
        val width = p.width.coerceAtMost(60)
        val offset = 60 - width
        for (i in 0 until width) {
            val v = buckets[offset + i]
            val level = if (v == 0) 0 else ((v * (Glyphs.RAMP_V.length - 2)) / max + 1).coerceIn(1, Glyphs.RAMP_V.length - 1)
            p.put(i, 0, Glyphs.RAMP_V[level], if (errors[offset + i] > 0) Palette.bad else Palette.info)
        }
        p.text(0, 1, "-${width}m", Palette.textDim)
        p.textRight(width, 1, "now · peak $max/min", Palette.textDim)
        val lastMinute = buckets[59]
        val lastTen = buckets.takeLast(10).sum() / 10.0
        p.text(0, 2, "${entries.size} requests in the hour · $lastMinute in the last minute · %.1f/min over ten".format(lastTen), Palette.text)
        val stats = model.apiStats()
        p.text(0, 3, "this process: ${stats.requests} requests, ${stats.errors} errors, ${stats.throttled} throttled".take(p.width), if (stats.throttled.get() > 0) Palette.warn else Palette.textDim)
        val pacer = model.pacer()
        p.text(0, 4, "pacer: ${pacer.queued} queued, ${pacer.granted} granted, ${pacer.idleGranted} of them idle".take(p.width), Palette.textDim)
        p.text(0, 5, "queue ", Palette.textDim)
        pacer.queueHistory.forEachIndexed { i, q ->
            val glyph = Glyphs.RAMP_V[q.coerceIn(0, Glyphs.RAMP_V.length - 1)]
            p.put(6 + i, 5, glyph, when { q <= 7 -> Palette.good; q <= 15 -> Palette.warn; else -> Palette.bad })
        }
        val byLane = entries.groupingBy { it.priority.lowercase() }.eachCount().entries.sortedByDescending { it.value }.joinToString(" · ") { "${it.key} ${it.value}" }
        p.text(0, 6, "by lane: $byLane".take(p.width), Palette.textDim)
        val status = model.galaxy.status()
        status?.health?.lastMarketUpdate?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let {
            p.text(0, 7, "server: ${status.status.take(p.width - 40)} · markets updated ${Format.age(it, now)} ago".take(p.width), Palette.textDim)
        }
    }

    private fun run(p: Painter, model: BridgeModel) {
        val snap = model.snapshot()
        val now = model.now()
        val runner = snap.runner
        var y = 0
        when {
            runner == null -> p.text(0, y++, "nobody is running the plan", Palette.warn)
            runner.isLive(now, behaviour.decisions.Intentions.processAlive) -> {
                p.text(0, y++, "run pid ${runner.pid} is alive", Palette.good)
                p.text(0, y++, "started ${Format.age(runner.started, now)} ago · heartbeat ${Format.age(runner.heartbeat, now)} ago", Palette.text)
            }
            else -> {
                p.text(0, y++, "run pid ${runner.pid} is gone", Palette.bad)
                p.text(0, y++, "last heartbeat ${Format.age(runner.heartbeat, now)} ago", Palette.textDim)
            }
        }
        val plan = snap.plan
        if (plan != null) {
            p.text(0, y++, "plan: phase ${plan.phase.name}, ${plan.assignments.size} assignments, ${plan.goals.fleet.size} fleet goals, ${plan.chains.size} chains".take(p.width), Palette.text)
            val unassigned = snap.ships.keys.filter { plan.assignmentFor(it) == null }
            if (unassigned.isNotEmpty()) p.text(0, y++, "unassigned: ${unassigned.joinToString(", ") { it.substringAfterLast('-') }}".take(p.width), Palette.warn)
            val notRunning = plan.assignments.filter { snap.shipStatus[it.ship] == null }
            if (notRunning.isNotEmpty()) p.text(0, y++, "assigned but not running: ${notRunning.joinToString(", ") { it.ship.substringAfterLast('-') }}".take(p.width), Palette.warn)
        } else p.text(0, y++, "no plan file", Palette.textDim)
        val failed = snap.shipStatus.filter { it.value.phase == "failed" }
        if (failed.isNotEmpty()) p.text(0, y++, "failed: ${failed.keys.joinToString(", ") { it.substringAfterLast('-') }}".take(p.width), Palette.bad)
        snap.resetDate?.let { p.text(0, y++, "reset $it" + (snap.nextReset?.let { n -> runCatching { Instant.parse(n) }.getOrNull()?.let { " · next in ${Format.span(Duration.between(now, it))}" } } ?: ""), Palette.textDim) }
    }

    private fun console(p: Painter, model: BridgeModel) {
        var y = 0
        p.text(0, y++, "%2.0f fps · %.1f ms render · %d B/frame".format(model.fps, model.renderMillis, model.lastFrameBytes), Palette.text)
        p.text(0, y++, "${model.width}x${model.height} · ${model.mode}", Palette.textDim)
        p.text(0, y++, model.ttyDescription.take(p.width), Palette.textDim)
        p.text(0, y++, "dots: ${model.dots.name.lowercase()} · java ${System.getProperty("java.version")}", Palette.textDim)
        p.text(0, y++, "feed ${model.feed().size} lines · galaxy ${model.galaxy.systems().size} systems, ${model.galaxy.gatesMapped} gates".take(p.width), Palette.textDim)
        model.galaxy.progress?.let { p.text(0, y++, it.take(p.width), Palette.warn) }
    }

    private fun problemLines(): List<Line> {
        val m = model ?: return emptyList()
        val now = m.now()
        return m.problems().sortedByDescending { it.at }.map { pr ->
            Line("${Format.age(pr.at, now).padStart(4)} ${pr.text}", if (pr.severe) Palette.bad else Palette.warn)
        }
    }

    private fun staleRows(model: BridgeModel): List<Table.Row> {
        val snap = model.snapshot()
        val now = model.now()
        return snap.markets.values
            .filter { it.hasPrices && Duration.between(it.lastRead, now).toHours() >= 3 }
            .sortedBy { it.lastRead }
            .map { m ->
                val age = Duration.between(m.lastRead, now)
                Table.Row(m.symbol, listOf(m.symbol, model.snapshot().waypoints[m.symbol]?.systemSymbol ?: m.symbol.substringBeforeLast('-'), Format.span(age) + " ago", m.tradeGoods.size.toString()), if (age.toHours() >= 12) Palette.warn else Palette.text)
            }
    }

    /** Ships whose current status names the same behaviour, phase and waypoint: usually a plan sending two to one job. */
    private fun conflictLines(): List<Line> {
        val m = model ?: return emptyList()
        val snap = m.snapshot()
        val waypoint = Regex("X1-[A-Z0-9]+-[A-Z0-9]+")
        val groups = snap.shipStatus.entries
            .filter { it.value.phase !in behaviour.decisions.Idle.IDLE_PHASES }
            .mapNotNull { (ship, status) ->
                val target = waypoint.findAll(status.detail).map { it.value }.lastOrNull() ?: return@mapNotNull null
                Triple(status.behaviour, target, ship)
            }
            .groupBy { it.first to it.second }
            .filter { it.value.size > 1 }
        return groups.map { (key, ships) ->
            Line("${key.first} at ${key.second}: ${ships.joinToString(", ") { it.third.substringAfterLast('-') }}", Palette.warn)
        }
    }

    private fun logLines(): List<Line> {
        val m = model ?: return emptyList()
        return m.logTail().map { line ->
            val severe = " ERROR " in line
            Line(line, if (severe) Palette.bad else Palette.warn)
        }
    }

    @Suppress("unused")
    private fun healthNote(model: BridgeModel): String? {
        val snap = model.snapshot()
        val worst = Summary.marketHealth(snap, model.now()).minByOrNull { it.score } ?: return null
        return "${worst.system} health ${(worst.score * 100).toInt()}%"
    }
}

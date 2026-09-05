package bridge.views

import bridge.BridgeModel
import bridge.canvas.Attr
import bridge.canvas.DotCanvas
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Rgb
import bridge.fx.Starfield
import bridge.glyphs.Palette
import bridge.tty.Input
import model.BootProgress
import kotlin.math.sin

/**
 * Milestone 0: proves the terminal. A starfield that twinkles, a marker that drifts, the last
 * inputs with their coordinates, a crosshair where the mouse was last clicked, and a row of every
 * glyph family the console will use, so a font that lacks one shows itself here and nowhere else.
 */
class SpikeView : View {
    override val title = "Spike"
    private val stars = Starfield(seed = 7)

    override fun paint(p: Painter, model: BridgeModel, t: Double) {
        stars.paint(p, t)
        header(p, model, t)

        val body = Rect(0, 1, p.width, p.height - 2)
        val (left, right) = body.cols(Len.fixed(40), Len.weight())
        val (agentRect, inputRect) = left.rows(Len.fixed(9), Len.weight())
        val (glyphRect, spaceRect) = right.rows(Len.fixed(16), Len.weight())

        agentPanel(p.panel(agentRect, "Agent"), model)
        inputPanel(p.panel(inputRect, "Input", hint = "click, scroll, type"), model)
        glyphPanel(p.panel(glyphRect, "Glyph test", hint = "every row should look even"), t)
        space(p.sub(spaceRect), model, t)

        val status = "q quit · resize the window · every glyph row above should be one height, no gaps"
        p.fill(Rect(0, p.height - 1, p.width, 1), ' ', Palette.textDim, Palette.panel)
        p.text(1, p.height - 1, status, Palette.textDim, Palette.panel)
        model.lastClick?.let { (cx, cy) ->
            p.put(cx, cy, '┼', Palette.accent, null, Attr.BOLD)
        }
    }

    private fun header(p: Painter, model: BridgeModel, t: Double) {
        p.fill(Rect(0, 0, p.width, 1), ' ', Palette.text, Palette.panel)
        p.text(1, 0, "TRADEY", Palette.accent, Palette.panel, Attr.BOLD)
        p.text(8, 0, "bridge", Palette.textDim, Palette.panel)
        val snap = model.snapshot()
        val agent = snap.agent
        if (agent != null) {
            p.text(16, 0, agent.symbol, Palette.textBright, Palette.panel, Attr.BOLD)
            p.text(17 + agent.symbol.length, 0, "${agent.startingFaction}  ${"%,d".format(agent.credits)} cr  ${snap.ships.size} ships", Palette.text, Palette.panel)
        } else {
            p.text(16, 0, BootProgress.current ?: BootProgress.failure ?: "not booted", Palette.textDim, Palette.panel)
        }
        val right = "%dx%d  %2.0f fps  %4.1f ms  %5d B/frame  %6.1fs".format(p.width, p.height, model.fps, model.renderMillis, model.lastFrameBytes, t)
        p.textRight(p.width - 1, 0, right, Palette.textDim, Palette.panel)
    }

    private fun agentPanel(p: Painter, model: BridgeModel) {
        var y = 0
        val snap = model.snapshot()
        val agent = snap.agent
        if (agent == null) {
            p.text(0, y++, BootProgress.title.ifEmpty { "boot" }, Palette.title)
            BootProgress.done.takeLast(4).forEach { p.text(0, y++, "✓ $it".take(p.width), Palette.good) }
            BootProgress.current?.let { p.text(0, y++, "… $it".take(p.width), Palette.warn) }
            BootProgress.failure?.let { p.text(0, y++, it.take(p.width), Palette.bad) }
        } else {
            p.text(0, y++, agent.symbol, Palette.textBright, null, Attr.BOLD)
            p.text(0, y++, "${agent.startingFaction}  hq ${agent.headquarters}", Palette.text)
            p.text(0, y++, "${"%,d".format(agent.credits)} credits", Palette.good)
            p.text(0, y++, "${snap.ships.size} ships, ${snap.waypoints.size} waypoints, ${snap.markets.size} markets", Palette.text)
            val phase = snap.plan?.phase?.name ?: "no plan"
            p.text(0, y++, "phase $phase", Palette.accent)
        }
        y = p.height - 2
        p.text(0, y++, model.ttyDescription.take(p.width), Palette.textDim)
        p.text(0, y, "java ${System.getProperty("java.version")}  ${model.mode}".take(p.width), Palette.textDim)
    }

    private fun inputPanel(p: Painter, model: BridgeModel) {
        val events = model.recentInputs
        if (events.isEmpty()) {
            p.text(0, 0, "nothing yet", Palette.textDim)
            return
        }
        events.takeLast(p.height).forEachIndexed { i, e ->
            val colour = when (e) {
                is Input.Key -> Palette.text
                is Input.Mouse -> if (e.isPress) Palette.accent else if (e.isWheel) Palette.info else Palette.textDim
            }
            p.text(0, i, e.toString().take(p.width), colour)
        }
    }

    private fun glyphPanel(p: Painter, t: Double) {
        val rows = listOf(
            "box      ╭─┬─╮ │ ├─┼─┤ ╰─┴─╯ ┌┐└┘ ═║╔╗╚╝",
            "blocks   ▁▂▃▄▅▆▇█ ▏▎▍▌▋▊▉█ ▀▄ ░▒▓█ ▘▝▖▗▚▞",
            "braille  ⠁⠃⠇⡇⣇⣧⣷⣿ ⠉⠒⠤⣀ ⢀⢠⢰⢸ ⡏⡗⡧⣇",
            "shapes   ●○◉◎◐◑◒◓ ◆◇◈ ■□▪▫ ▲▼◀▶△▽ ★☆",
            "arrows   ← ↑ → ↓ ↖ ↗ ↘ ↙ ⇐ ⇒ ↔ ↕ ⟵ ⟶",
            "misc     ° · ˙ ∙ • ‥ … ‰ ± × ÷ ≈ ≠ ≤ ≥ ∞ § ¶",
        )
        rows.forEachIndexed { i, r ->
            p.text(0, i, r.substring(0, 9), Palette.textDim)
            p.text(9, i, r.substring(9), Palette.textBright)
        }
        // 24-bit colour: a smooth hue sweep, and a brightness ramp of the accent.
        val y = rows.size + 1
        p.text(0, y, "colour   ", Palette.textDim)
        val sweep = p.width - 10
        for (x in 0 until sweep) {
            p.put(9 + x, y, '█', Rgb.hsv(360.0 * x / sweep, 0.8, 0.9))
        }
        for (x in 0 until sweep) {
            p.put(9 + x, y + 1, '█', Palette.background.mix(Palette.accent, x.toDouble() / sweep))
        }
        // The same curve twice: braille on the left, half blocks on the right, both moving.
        val cy = y + 3
        val half = (p.width - 10) / 2
        p.text(0, cy, "curve    ", Palette.textDim)
        p.text(9, cy, "in braille dots (2×4 per cell)", Palette.textDim)
        p.text(9 + half, cy, "in half blocks (1×2 per cell)", Palette.textDim)
        wave(p, Rect(9, cy + 1, half - 1, 3), DotCanvas.Mode.BRAILLE, t)
        wave(p, Rect(9 + half, cy + 1, half - 1, 3), DotCanvas.Mode.HALF, t)
    }

    private fun wave(p: Painter, r: Rect, mode: DotCanvas.Mode, t: Double) {
        if (r.w <= 0 || r.h <= 0) return
        val c = DotCanvas(r.w, r.h, mode)
        var prev: Pair<Int, Int>? = null
        for (dx in 0 until c.dotsW) {
            val phase = dx.toDouble() / c.dotsW * Math.PI * 4 + t * 2
            val dy = ((0.5 - 0.45 * sin(phase)) * (c.dotsH - 1)).toInt()
            val colour = Palette.info.mix(Palette.good, dx.toDouble() / c.dotsW)
            prev?.let { (px, py) -> c.line(px, py, dx, dy, colour) } ?: c.set(dx, dy, colour)
            prev = dx to dy
        }
        c.paint(p, r.x, r.y)
    }

    /** Open space: a marker on a slow orbit, leaving a fading braille trail. */
    private fun space(p: Painter, model: BridgeModel, t: Double) {
        if (p.width < 10 || p.height < 5) return
        val c = DotCanvas(p.width, p.height, model.dots)
        val cx = c.dotsW / 2.0
        val cy = c.dotsH / 2.0
        val rx = c.dotsW * 0.38
        val ry = c.dotsH * 0.38
        val steps = 90
        for (i in steps downTo 0) {
            val a = t * 0.6 - i * 0.03
            val x = (cx + rx * kotlin.math.cos(a)).toInt()
            val y = (cy + ry * sin(a)).toInt()
            val fade = 1.0 - i.toDouble() / steps
            c.set(x, y, Palette.background.mix(Palette.accent, 0.15 + 0.85 * fade * fade))
        }
        c.paint(p, 0, 0)
        val a = t * 0.6
        val sx = ((cx + rx * kotlin.math.cos(a)) / model.dots.dotsX).toInt()
        val sy = ((cy + ry * sin(a)) / model.dots.dotsY).toInt()
        p.put(sx, sy, '◆', Palette.textBright, null, Attr.BOLD)
        p.text(1, 0, "space: a ship on a slow orbit with a trail in ${model.dots.name.lowercase()} dots  (d toggles)", Palette.textDim)
    }

    override fun onInput(input: Input, model: BridgeModel): Boolean {
        if (input is Input.Key && input.key == "d") {
            model.dots = if (model.dots == DotCanvas.Mode.BRAILLE) DotCanvas.Mode.HALF else DotCanvas.Mode.BRAILLE
            return true
        }
        return false
    }
}

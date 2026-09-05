package bridge

import bridge.canvas.Attr
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.glyphs.Palette
import bridge.scene.Keys
import bridge.tty.Input
import bridge.views.View

/**
 * The frame around the views: a tab bar along the bottom naming every screen, number and function
 * keys or a click to switch between them, and the quit keys. Everything above the bar is the
 * current view's.
 */
class Shell(val views: List<View>, private val model: BridgeModel) {
    var current = 0
        private set

    val view: View get() = views[current]

    fun show(index: Int) {
        if (index in views.indices) current = index
    }

    fun show(name: String): Boolean {
        val i = views.indexOfFirst { it.title.equals(name, ignoreCase = true) }
        if (i < 0) return false
        current = i
        return true
    }

    fun paint(p: Painter, t: Double) {
        val body = Rect(0, 0, p.width, p.height - 1)
        view.paint(p.sub(body), model, t)
        tabBar(p, Rect(0, p.height - 1, p.width, 1))
    }

    private var tabSpans: List<Pair<IntRange, Int>> = emptyList()

    private fun tabBar(p: Painter, r: Rect) {
        p.fill(r, ' ', Palette.textDim, Palette.panel)
        var x = 1
        val spans = ArrayList<Pair<IntRange, Int>>()
        views.forEachIndexed { i, v ->
            val label = " ${i + 1} ${v.title} "
            val active = i == current
            p.text(x, r.y, label, if (active) Palette.textBright else Palette.textDim, if (active) Palette.selection else Palette.panel, if (active) Attr.BOLD else Attr.NONE)
            spans += (x until x + label.length) to i
            x += label.length + 1
        }
        tabSpans = spans
        p.textRight(r.right - 1, r.y, "Tab focus · q quit", Palette.textDim, Palette.panel)
    }

    /** True when the console should quit. */
    fun onInput(input: Input): Boolean {
        val quit = route(input)
        model.pendingView?.let { show(it); model.pendingView = null }
        return quit
    }

    private fun route(input: Input): Boolean {
        when (input) {
            is Input.Key -> {
                val key = Keys.normalise(input.key)
                if (key == "q" || key == "Escape" || (input.ctrl && key == "c")) return true
                if (view.onInput(input, model)) return false
                key.toIntOrNull()?.let { n -> if (n in 1..views.size) show(n - 1) }
                if (key.length in 2..3 && key[0] == 'F') key.drop(1).toIntOrNull()?.let { n -> if (n in 1..views.size) show(n - 1) }
            }
            is Input.Mouse -> {
                if (input.left && input.y == model.height - 1) {
                    tabSpans.firstOrNull { input.x in it.first }?.let { show(it.second) }
                    return false
                }
                view.onInput(input, model)
            }
        }
        return false
    }
}

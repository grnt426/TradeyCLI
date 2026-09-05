package bridge.views

import bridge.BridgeModel
import bridge.canvas.Painter
import bridge.scene.Keys
import bridge.scene.Scene
import bridge.scene.Widget
import bridge.tty.Input

/**
 * A view made of widgets. Subclasses build a [Scene] in [paint] by placing widgets; this class
 * routes input: Tab and Shift+Tab move focus through the focusable widgets in the order they were
 * placed, keys go to the focused widget, the mouse goes to whatever is under it and focuses it.
 */
abstract class WidgetView : View {
    protected var scene = Scene()
    private var placedFocusables: List<Widget> = emptyList()
    var focus: Widget? = null
        protected set

    /** Starts a fresh scene for this frame; call first in [paint]. */
    protected fun beginFrame(): Scene {
        scene = Scene()
        return scene
    }

    /** Places [w], focusing it when nothing is focused yet. */
    protected fun place(w: Widget, p: Painter, t: Double) {
        if (focus == null && w.focusable) focus = w
        scene.place(w, p, focus === w, t)
    }

    /** Call last in [paint], with every focusable widget in tab order. */
    protected fun endFrame(focusables: List<Widget>) {
        placedFocusables = focusables
        if (focus !in focusables) focus = focusables.firstOrNull()
    }

    override fun onInput(input: Input, model: BridgeModel): Boolean {
        when (input) {
            is Input.Key -> {
                if (Keys.normalise(input.key) == "Tab") {
                    cycleFocus(if (input.shift) -1 else 1)
                    return true
                }
                return focus?.onKey(input) ?: false
            }
            is Input.Mouse -> {
                val target = scene.hit(input.x, input.y) ?: return false
                if (input.isPress && target.focusable) focus = target
                return target.onMouse(input, input.x - target.rect.x, input.y - target.rect.y)
            }
        }
    }

    private fun cycleFocus(step: Int) {
        if (placedFocusables.isEmpty()) return
        val i = placedFocusables.indexOf(focus)
        focus = placedFocusables[((i + step) % placedFocusables.size + placedFocusables.size) % placedFocusables.size]
    }
}

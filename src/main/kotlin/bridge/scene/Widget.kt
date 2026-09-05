package bridge.scene

import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.tty.Input

/**
 * Something that paints into a rectangle and may take input. A widget does not know where it is
 * until a view places it each frame through a [Scene], which records the rectangle for hit testing.
 */
abstract class Widget {
    /** Where the widget was last painted, in screen cells. */
    var rect: Rect = Rect(0, 0, 0, 0)

    open val focusable: Boolean = false

    abstract fun paint(p: Painter, focused: Boolean, t: Double)

    /** True when the key was used. */
    open fun onKey(key: Input.Key): Boolean = false

    /** [x] and [y] are relative to the widget's rectangle. True when the event was used. */
    open fun onMouse(m: Input.Mouse, x: Int, y: Int): Boolean = false
}

/** One frame's placements: which widget owns which cells, for routing the mouse. */
class Scene {
    private val placed = ArrayList<Widget>()

    /** Paints [widget] into [p] and remembers where it went. */
    fun place(widget: Widget, p: Painter, focused: Boolean, t: Double) {
        widget.rect = p.clip
        placed += widget
        widget.paint(p, focused, t)
    }

    /** The widget under ([x], [y]); the last one placed wins, so a widget painted on top is hit first. */
    fun hit(x: Int, y: Int): Widget? = placed.lastOrNull { it.rect.contains(x, y) }
}

/** Keys as Mordant names them, with the aliases other layers use, so views compare against one name. */
object Keys {
    fun normalise(key: String): String = when (key) {
        "Up" -> "ArrowUp"
        "Down" -> "ArrowDown"
        "Left" -> "ArrowLeft"
        "Right" -> "ArrowRight"
        "Return" -> "Enter"
        "Esc" -> "Escape"
        "PgUp" -> "PageUp"
        "PgDn" -> "PageDown"
        else -> key
    }
}

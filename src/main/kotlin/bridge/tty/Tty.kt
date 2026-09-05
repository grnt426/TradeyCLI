package bridge.tty

import kotlin.time.Duration

data class Size(val width: Int, val height: Int)

/** What the terminal sent us. Coordinates are cells, 0-based, from the top-left. */
sealed interface Input {
    data class Key(val key: String, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) : Input {
        override fun toString(): String = buildString {
            if (ctrl) append("Ctrl+")
            if (alt) append("Alt+")
            if (shift && key.length > 1) append("Shift+")
            append(key)
        }
    }

    data class Mouse(
        val x: Int,
        val y: Int,
        val left: Boolean = false,
        val right: Boolean = false,
        val middle: Boolean = false,
        val wheelUp: Boolean = false,
        val wheelDown: Boolean = false,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    ) : Input {
        val isPress: Boolean get() = left || right || middle
        val isWheel: Boolean get() = wheelUp || wheelDown
        override fun toString(): String = buildString {
            append("Mouse ").append(x).append(',').append(y)
            if (left) append(" left")
            if (right) append(" right")
            if (middle) append(" middle")
            if (wheelUp) append(" wheel-up")
            if (wheelDown) append(" wheel-down")
            if (ctrl) append(" ctrl")
            if (alt) append(" alt")
            if (shift) append(" shift")
            if (!isPress && !isWheel) append(" release")
        }
    }
}

/**
 * The terminal, reduced to what the console needs: a full screen we own, its size, raw input with
 * the mouse, and a place to write frames. Implemented over Mordant ([MordantTty]); anything else
 * that can do these five things would do.
 */
interface Tty : AutoCloseable {
    /** Alternate screen, cursor hidden, raw mode with mouse reporting. */
    fun enter()

    /** The current size, asked of the terminal each time so a resize is seen on the next frame. */
    fun size(): Size

    /** The next event, or null when [timeout] passes without one. */
    fun poll(timeout: Duration): Input?

    fun write(s: CharSequence)

    /** A one-line description of what was detected, for the diagnostics panel. */
    fun describe(): String

    /** Leaves raw mode and the alternate screen; the shell is as it was. */
    override fun close()
}

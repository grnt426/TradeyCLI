package bridge.tty

import com.github.ajalt.mordant.input.KeyboardEvent
import com.github.ajalt.mordant.input.MouseEvent
import com.github.ajalt.mordant.input.MouseTracking
import com.github.ajalt.mordant.input.RawModeScope
import com.github.ajalt.mordant.input.enterRawMode
import com.github.ajalt.mordant.terminal.Terminal
import kotlin.time.Duration

/** [Tty] over Mordant: its native module does raw mode and console input on Windows. */
class MordantTty(private val terminal: Terminal = Terminal()) : Tty {
    private var raw: RawModeScope? = null

    override fun enter() {
        // Alternate screen, cursor off, clear. Mordant has no alternate-screen helper, so these are written raw.
        terminal.rawPrint("\u001b[?1049h\u001b[?25l\u001b[0m\u001b[2J\u001b[H")
        raw = terminal.enterRawMode(MouseTracking.Normal)
    }

    override fun size(): Size = terminal.updateSize().let { Size(it.width, it.height) }

    override fun poll(timeout: Duration): Input? {
        val scope = raw ?: return null
        return when (val e = scope.readEventOrNull(timeout)) {
            null -> null
            is KeyboardEvent -> Input.Key(e.key, e.ctrl, e.alt, e.shift)
            is MouseEvent -> Input.Mouse(
                e.x, e.y, e.left, e.right, e.middle, e.wheelUp, e.wheelDown, e.ctrl, e.alt, e.shift
            )
            else -> null
        }
    }

    override fun write(s: CharSequence) {
        terminal.rawPrint(s)
    }

    override fun describe(): String {
        val info = terminal.terminalInfo
        return "ansi ${info.ansiLevel} interactive=${info.outputInteractive}/${info.inputInteractive} " +
                "iface=${terminal.terminalInterface::class.simpleName}"
    }

    override fun close() {
        try {
            raw?.close()
        } finally {
            raw = null
            terminal.rawPrint("\u001b[0m\u001b[?25h\u001b[?1049l")
        }
    }
}

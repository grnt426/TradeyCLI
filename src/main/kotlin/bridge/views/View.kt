package bridge.views

import bridge.BridgeModel
import bridge.canvas.Painter
import bridge.tty.Input

/**
 * A screen. [paint] is pure: the same model, size and time give the same frame, which is what
 * `--frame` and the golden tests rely on. Input arrives through [onInput]; a view that does not
 * handle an event returns false and the console's global bindings get it.
 */
interface View {
    val title: String
    fun paint(p: Painter, model: BridgeModel, t: Double)
    fun onInput(input: Input, model: BridgeModel): Boolean = false
}

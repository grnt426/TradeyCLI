package bridge.glyphs

import bridge.canvas.Rgb

/**
 * The console's colours, in one place. Everything that paints picks from here; hues for waypoint
 * types and goods come from tables beside this, never from literals in a view.
 */
object Palette {
    val background = Rgb(7, 9, 18)
    val panel = Rgb(11, 14, 26)
    val border = Rgb(58, 66, 96)
    val borderFocused = Rgb(140, 120, 230)
    val title = Rgb(170, 175, 200)
    val text = Rgb(200, 205, 218)
    val textDim = Rgb(112, 120, 142)
    val textBright = Rgb(240, 242, 248)
    val track = Rgb(22, 26, 42)
    val selection = Rgb(52, 44, 96)
    val selectionDim = Rgb(28, 30, 50)

    /** The faction's colour; VOID is violet. Replaced at boot when the agent's faction is known. */
    var accent = Rgb(150, 110, 240)

    val good = Rgb(84, 200, 124)
    val warn = Rgb(232, 190, 72)
    val bad = Rgb(222, 82, 72)
    val info = Rgb(92, 160, 236)

    /** Star tints: white, blue-white, warm. */
    val stars = listOf(Rgb(190, 195, 215), Rgb(150, 170, 235), Rgb(225, 200, 160))
}

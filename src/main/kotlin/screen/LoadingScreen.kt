package screen

import AppState
import AppState.BOOT
import HEADER_COLOR
import com.varabyte.kotter.foundation.anim.text
import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.foundation.input.input
import com.varabyte.kotter.foundation.text.green
import com.varabyte.kotter.foundation.text.red
import com.varabyte.kotter.foundation.text.rgb
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import getActiveAppState
import isActiveScreen
import model.BootProgress

/**
 * Shown while a boot action runs on the engine scope. The spinner is a Kotter animation, so the
 * section re-renders on every frame and picks up progress without anyone calling rerender.
 */
class LoadingScreen : Screen() {

    private val self = this

    override fun MainRenderScope.render() {
        val failure = BootProgress.failure
        rgb(HEADER_COLOR.rgb) { textLine(BootProgress.title) }
        BootProgress.done.forEach { step ->
            green { text("  done  ") }
            textLine(step)
        }
        BootProgress.current?.let { step ->
            text("  ")
            text(TextAnimationContainer.spinner!!)
            textLine("  $step")
        }
        if (failure != null) {
            textLine()
            red { textLine(failure) }
            textLine("Press Enter to go back to the menu.")
            text("> ")
            input()
        }
    }

    override fun OnInputEnteredScope.onInput(runScope: RunScope): AppState {
        if (!isActiveScreen(self)) return getActiveAppState()
        clearInput()
        return if (BootProgress.failure != null) BOOT else getActiveAppState()
    }

    override fun OnKeyPressedScope.onKeyPressed(runScope: RunScope): AppState = getActiveAppState()
}

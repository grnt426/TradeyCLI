package screen

import AppState
import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope

abstract class Screen {
    abstract fun MainRenderScope.render()
    abstract fun OnInputEnteredScope.onInput(runScope: RunScope): AppState
    abstract fun OnKeyPressedScope.onKeyPressed(runScope: RunScope): AppState
    open fun getActiveSelectedScreen(): Any = Object()
    open fun isActiveSubScreen(sub: SubScreen<*>) = false
}
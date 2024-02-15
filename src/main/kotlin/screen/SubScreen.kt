package screen

import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope

abstract class SubScreen<T>(val p: Screen) {
    abstract fun MainRenderScope.render()

    abstract fun OnInputEnteredScope.onInput(runScope: RunScope): T

    abstract fun OnKeyPressedScope.onKeyPressed(runScope: RunScope): T
}
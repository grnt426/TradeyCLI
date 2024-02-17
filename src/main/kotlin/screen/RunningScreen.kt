package screen

import AppState
import com.varabyte.kotter.foundation.input.OnInputEnteredScope
import com.varabyte.kotter.foundation.input.OnKeyPressedScope
import com.varabyte.kotter.runtime.MainRenderScope
import com.varabyte.kotter.runtime.RunScope
import getActiveAppState
import isActiveScreen
import screen.RunningScreen.SelectedScreen.CONSOLE
import screen.RunningScreen.SelectedScreen.SYSTEM

object RunningScreen : Screen() {

    val self = this

    enum class SelectedScreen(val screen: SubScreen<SelectedScreen>) {
        CONSOLE(ConsoleSubScreen(self)),
        SYSTEM(SystemSubScreen(self)),
    }

    var selectedScreen = CONSOLE
    override fun MainRenderScope.render() {
        with(selectedScreen.screen) {
            render()
        }
    }

    override fun OnInputEnteredScope.onInput(runScope: RunScope): AppState {
        if (isActiveScreen(self)) {
            with(selectedScreen.screen) {
                return when (onInput(runScope)) {
                    CONSOLE -> {
                        selectedScreen = CONSOLE
                        getActiveAppState()
                    }

                    SYSTEM -> {
                        selectedScreen = SYSTEM
                        getActiveAppState()
                    }
                }
            }
        } else {
            return getActiveAppState()
        }
    }

    override fun OnKeyPressedScope.onKeyPressed(runScope: RunScope): AppState {
        if (isActiveScreen(self)) {
            with(selectedScreen.screen) {
                return when (onKeyPressed(runScope)) {
                    CONSOLE -> {
                        selectedScreen = CONSOLE
                        getActiveAppState()
                    }

                    SYSTEM -> {
                        selectedScreen = SYSTEM
                        getActiveAppState()
                    }
                }
            }
        } else {
            return getActiveAppState()
        }
    }

    override fun getActiveSelectedScreen(): SelectedScreen = selectedScreen
    override fun isActiveSubScreen(sub: SubScreen<*>): Boolean = this.selectedScreen.screen == sub
}
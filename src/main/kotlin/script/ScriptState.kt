package script

import java.time.LocalDateTime

class ScriptState(
    val check: ScriptCheck,
    val action: ScriptAction,
    val transitions: List<ScriptState>
) {
    fun canTransition(): Boolean = check.isSatisfied()

    fun execute():LocalDateTime = action.doAction()

    fun getState(): ScriptState? = transitions.firstOrNull { t -> t.canTransition() }
}
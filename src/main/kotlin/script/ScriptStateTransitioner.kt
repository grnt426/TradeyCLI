package script

import java.time.LocalDateTime

class ScriptStateTransitioner(
    val scriptStart: List<ScriptState>,
    var running: Boolean = false,
    var done: Boolean = false
) {
    lateinit var curState: ScriptState
    var actionFinishedOn = LocalDateTime.now()
    fun startScript() {
        scriptStart.firstOrNull{ t -> t.canTransition() }?.let {
            actionFinishedOn = it.execute()
            curState = it
            running = true
            it
        }
    }

    fun poll() {
        if (actionFinishedOn < LocalDateTime.now()) {
            if (!running) {
                startScript()
            } else if (curState.transitions.isEmpty()) {
                running = false
                done = true
            } else {
                curState.getState()?.let {
                    curState = it
                    actionFinishedOn = curState.execute()
                }
            }
        }
    }
}
package script

import data.ship.Ship
import java.util.*
import kotlin.concurrent.timer

class Script internal constructor() {

    val data = mutableMapOf<String, String>()
    val states = mutableListOf<State>()
    var scriptStatus = ScriptStatus.UNSTARTED
    var scheduledScript: Timer? = null

    fun state(cond: () -> Boolean = { true }, scope: StateScope.() -> Unit): State {
        println("Creating state")
        val state = State(cond, scope)
        states.add(state)
        return state
    }

    fun stopScript() {
        scheduledScript?.cancel()
        scriptStatus = ScriptStatus.FINISHED
    }

    fun runOnce() {
        scriptStatus = ScriptStatus.RUNNING
        StateScope().apply { findRunnableState() }
        scriptStatus = ScriptStatus.FINISHED
    }

    private fun StateScope.findRunnableState() {
        val s = states.firstOrNull { s -> s.canRun() }
        println("Running stuff ${s ?: "Nothing"}")
        s?.scope?.let { it(this) }
    }

    /**
     * Will execute this script on a periodic basis, as defined by the interval parameter.
     *
     * @param interval - milliseconds between executions of script
     */
    fun runForever(interval: Long = 1_000) {
        scriptStatus = ScriptStatus.RUNNING
        scheduledScript = timer("sdfdf",true, 0, interval) {
            StateScope().apply { findRunnableState() }
        }
    }
}

fun script(init: Script.() -> Unit): Script {
    println("Creating script")
    return Script().apply{ init() }
}

class StateScope {

}

enum class ScriptStatus {

    // When not yet run
    UNSTARTED,

    // When `runOnce` or `runForever` is called
    RUNNING,

    // when `runOnce` finishes or `stopScript` is called
    FINISHED,

    // reserved
    ERRORED,
}
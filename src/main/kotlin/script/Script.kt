package script

import kotlin.concurrent.timer

class Script internal constructor() {

    val data = mutableMapOf<String, String>()
    val states = mutableListOf<State>()

    fun state(cond: () -> Boolean = { true }, scope: StateScope.() -> Unit): State {
        println("Creating state")
        val state = State(cond, scope)
        states.add(state)
        return state
    }

    fun runOnce() {
        StateScope().apply { findRunnableState() }
    }

    private fun StateScope.findRunnableState() {
        val s = states.firstOrNull { s -> s.canRun() }
        println("Running stuff ${s ?: "Nothing"}")
        s?.scope?.let { it(this) }
    }

    fun runForever() {
        timer("sdfdf",true, 0, 1_000) { StateScope().apply { runOnce() } }
    }
}

fun script(init: Script.() -> Unit): Script {
    println("Creating script")
    return Script().apply{ init() }
}

class StateScope {

}
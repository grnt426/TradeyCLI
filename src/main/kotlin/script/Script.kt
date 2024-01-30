package script

import data.ship.Ship
import io.ktor.util.*
import java.time.LocalDateTime

class Script internal constructor(scope: ScriptScope) {

    val data = mutableMapOf<String, String>()
    val states = mutableListOf<State>()

    fun state(cond: () -> Boolean, scope: StateScope.() -> Unit): State {
        println("Creating state")
        val newScope = StateScope()
        val state = State(cond, newScope)
        states.add(state)
        return state.apply { scope(newScope) }
    }

    fun run() {
        println("Running stuff ${states.firstOrNull { s -> s.canRun() } ?: "Nothing"}")
        states.first { s -> s.canRun() }.run()
    }

}

fun script(block: Script.() -> Unit): Script {
    println("Creating script")
    return Script(ScriptScope()).apply { block() }
}

@KtorDsl
class ScriptScope {
    var stateValue: Long = 0L
    var cooldownDateTime = LocalDateTime.now()
}

package script

import data.ship.Ship
import io.ktor.util.*
import java.time.LocalDateTime

class Script internal constructor(scope: ScriptScope) {

    val data = mutableMapOf<String, String>()
    val states = mutableListOf<State>()

    fun state(cond: () -> Boolean, scope: ScriptScope.() -> Unit) {
        states.add(State(cond, scope))
    }

    fun run() {
        states.first { s -> s.canRun() }.
    }

}

fun script(ship: Ship, block: Script.() -> Unit): Script {
    return Script(ScriptScope(ship)).apply { block() }
}

@KtorDsl
class ScriptScope(val ship: Ship) {
    var stateValue: Long = 0L
    var cooldownDateTime = LocalDateTime.now()
}

package script

import data.ship.Ship

class State(
    val cond: () -> Boolean,
    val scope: StateScope
) {

    fun canRun(): Boolean = cond()

    fun run() {
        println("Trying to run all ${scope.actions.size} actions enqueued")
        scope.actions.forEach { it() }
    }
}

class StateScope {

    init { println("Created StateScope") }

    val actions = mutableListOf<() -> Unit>()
    fun enqueueAction(action: () -> Unit) {
        println("Enqueued action")
        actions.add(action)
    }
}
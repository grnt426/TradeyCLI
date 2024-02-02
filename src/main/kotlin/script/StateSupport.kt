package script

class State(
    val cond: () -> Boolean,
    val scope: StateScope.() -> Unit
) {

    init { println("Created StateScope") }

    val actions = mutableListOf<() -> Unit>()
    fun enqueueAction(action: () -> Unit) {
        println("Enqueued action")
        actions.add(action)
    }

    fun canRun(): Boolean = cond()

    fun run() {
        println("Trying to run all ${actions.size} actions enqueued")
        actions.forEach { it() }
    }
}

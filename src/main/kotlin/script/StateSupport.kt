package script

class State(
    val cond: () -> Boolean,
    val scope: StateScope.() -> Unit
) {
    fun canRun(): Boolean = cond()
}

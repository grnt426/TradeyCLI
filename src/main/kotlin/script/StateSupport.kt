package script

class State(val cond: () -> Boolean, val scope: ScriptScope.() -> Unit) {

    fun canRun(): Boolean = cond()

    fun run() {  }
}

class StateScope(val action: ScriptAction) {
    // make mining action use state scope for ship to use

    // Somehow get all actions within a state scope

    // then run them?
}
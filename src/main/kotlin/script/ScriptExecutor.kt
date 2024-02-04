package script

import script.repo.MiningForemanScript

abstract class ScriptExecutor<T>(var currentState: T) {
    abstract fun execute()

    fun changeState(newState: T) {
        currentState = newState
    }

    fun <T> matchesState(transition: T): () -> Boolean {
        return {
            transition == currentState
        }
    }

    fun <T> isState(transition: T): Boolean = transition == currentState
}
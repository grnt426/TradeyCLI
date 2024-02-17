package script

import data.SavedScripts
import java.util.*

abstract class ScriptExecutor<T>(
    var currentState: T,
    val scriptType: String,
    val entityId: String? = null,
    var uuid: String = UUID.randomUUID().toString(),
) {

    companion object {
        var totalStateChanges = 0
    }
    abstract fun execute()

    fun saveState() {
        SavedScripts.saveState(uuid, currentState.toString(), entityId, scriptType)
    }

    fun changeState(newState: T) {
        totalStateChanges++
        currentState = newState
        saveState()
    }

    fun updateState(state: T) {
        currentState = state
    }

    fun <T> matchesState(transition: T): () -> Boolean {
        return {
            transition == currentState
        }
    }

    fun <T> isState(transition: T): Boolean = transition == currentState
}
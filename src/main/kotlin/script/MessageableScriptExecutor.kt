package script

import java.util.*

/**
 * I don't like this implementation. Think on it....
 */
abstract class MessageableScriptExecutor<S, M>(
    currentState: S,
    scriptType: String,
    entityId: String? = null,
    uuid: String = UUID.randomUUID().toString(),
): ScriptExecutor<S>(currentState, scriptType, entityId, uuid)  {

    private val messageQueue: MutableList<M> = mutableListOf()
    fun postMessage(message: M) {
        messageQueue.add(message)
    }

    fun consumeMessage(message: M): Boolean = messageQueue.removeAll { it == message }
}
package script

abstract class ScriptExecutor<T>(var startState: T) {
    abstract fun execute()
}
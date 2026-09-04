package model

import java.util.concurrent.CopyOnWriteArrayList

/**
 * What the boot sequence is doing right now, for the loading screen. Written by the engine
 * coroutine that runs the boot, read by the render thread.
 */
object BootProgress {
    @Volatile
    var title: String = ""
        private set

    /** Steps already completed, in order. */
    val done = CopyOnWriteArrayList<String>()

    @Volatile
    var current: String? = null
        private set

    @Volatile
    var failure: String? = null
        private set

    fun reset(title: String) {
        this.title = title
        done.clear()
        current = null
        failure = null
    }

    /** Marks the previous step done and starts [what]. */
    fun step(what: String) {
        current?.let { done += it }
        current = what
    }

    fun finish() {
        current?.let { done += it }
        current = null
    }

    fun fail(message: String) {
        current = null
        failure = message
    }
}

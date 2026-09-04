package app

import engine.Engine
import model.ProfileData

/** The one engine of this process and the profile it was started with. Line mode and the TUI both use it. */
object App {
    private val engineDelegate = lazy { Engine() }

    val engine: Engine by engineDelegate

    lateinit var profData: ProfileData

    /** Stops the engine and closes every client. Safe to call before anything was started. */
    fun shutdown() {
        if (engineDelegate.isInitialized()) engine.shutdown()
    }
}

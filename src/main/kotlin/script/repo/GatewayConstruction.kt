package script.repo

import script.ScriptExecutor
import script.script

class GatewayConstruction(val system: String): ScriptExecutor<GatewayConstruction.GatewayBuildStates>(GatewayBuildStates.IDLE) {

    enum class GatewayBuildStates {
        IDLE,
        CONSTRUCT
    }

    var target: String? = null

    override fun execute() {
        script {
            state(matchesState(GatewayBuildStates.IDLE)) {
                println("Not building anything")
            }
            state(matchesState(GatewayBuildStates.CONSTRUCT)) {
                // direct resources
            }
        }.runForever(30_000)
    }
}
package script.repo

import model.system.System
import script.ScriptExecutor
import script.script

class GatewayConstruction(val system: System): ScriptExecutor<GatewayConstruction.GatewayBuildStates>(
    GatewayBuildStates.IDLE, "GatewayConstruction", system.symbol
) {

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
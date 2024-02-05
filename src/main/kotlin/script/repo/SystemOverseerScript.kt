package script.repo

import model.system.System
import script.ScriptExecutor
import script.script

class SystemOverseerScript(val system: System): ScriptExecutor<SystemOverseerScript.SystemOversightState>(
    SystemOversightState.MAINTAIN, "SystemOverseerScript", system.symbol
) {

    enum class SystemOversightState {
        EXTRACTION,
        GATEWAY_CONSTRUCTION,
        MAINTAIN,
    }

    val systemForeman = MiningForemanScript(system)
    val gatewayConstruction = GatewayConstruction(system)

    override fun execute() {
        systemForeman.execute()
        gatewayConstruction.execute()
        script{
            state(matchesState(SystemOversightState.EXTRACTION)){
                systemForeman.changeState(MiningForemanScript.ForemanStates.EXPANDING)
            }
            state(matchesState(SystemOversightState.GATEWAY_CONSTRUCTION)) {
                systemForeman.changeState(MiningForemanScript.ForemanStates.MAINTAINING)

                if (gatewayConstruction.isState(GatewayConstruction.GatewayBuildStates.IDLE)) {
                    gatewayConstruction.target = "X1-HB1"
                    gatewayConstruction.changeState(GatewayConstruction.GatewayBuildStates.CONSTRUCT)
                }

                // direct construction, freight, purchase of mats

                // if gateway done, set to maintain
            }
            state(matchesState(SystemOversightState.MAINTAIN)) {
                println("Maintaining status quo in system.")
            }
        }.runForever()
    }
}
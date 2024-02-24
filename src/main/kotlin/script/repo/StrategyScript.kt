package script.repo

import model.GameState
import script.ScriptExecutor
import script.repo.mining.MiningForemanScript
import script.script

class StrategyScript: ScriptExecutor<StrategyScript.StrategyState>(
    StrategyState.INITIAL_PHASE, "StrategyScript"
) {

    enum class StrategyState {
        INITIAL_PHASE,
        INITIAL_EXPANSION,
        EXPANSION
    }

    val systems = mutableListOf<SystemOverseerScript>()
    val hq = SystemOverseerScript(GameState.getHqSystem())

    override fun execute() {
        hq.execute()
        script {
            state(matchesState(StrategyState.INITIAL_PHASE)) {

                // Expand our mining fleet to reach income goals
                systems.add(hq)
                hq.systemForeman.changeState(MiningForemanScript.ForemanStates.EXPANDING)
            }
            state(matchesState(StrategyState.INITIAL_EXPANSION)) {
                hq.changeState(SystemOverseerScript.SystemOversightState.GATEWAY_CONSTRUCTION)

                // if gateway done, change to expansion or whatever
            }
            state(matchesState(StrategyState.EXPANSION)) {
                // whatever at this point
            }
        }.runForever()
    }
}
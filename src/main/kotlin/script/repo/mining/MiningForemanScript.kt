package script.repo.mining

import model.hasCredits
import model.system.System
import script.ScriptExecutor
import script.script

class MiningForemanScript(val system: System) : ScriptExecutor<MiningForemanScript.ForemanStates>(
    ForemanStates.MAINTAINING, "MiningForemanScript", system.symbol
) {

    enum class ForemanStates {
        EXPANDING,
        MAINTAINING
    }

    val workers = mutableListOf<BasicMiningScript>()
    override fun execute() {
        script {
            state(matchesState(ForemanStates.EXPANDING)) {
                if (hasCredits(10_000)) {
                    // buy mining ship, assign script
                    // execute script
                }
                else {
                    println("Want to expand mining fleet, but not enough money.")
                }
            }
            state(matchesState(ForemanStates.MAINTAINING)) {
                println("No orders to expand")
            }
        }.runForever(5_000)
    }
}
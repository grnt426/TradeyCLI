package script.repo

import data.hasCredits
import script.ScriptExecutor
import script.script

class MiningForemanScript(system: String) : ScriptExecutor<MiningForemanScript.ForemanStates>(ForemanStates.MAINTAINING) {

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
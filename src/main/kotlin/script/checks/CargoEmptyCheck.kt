package script.checks

import script.ScriptCheck

class CargoEmptyCheck: ScriptCheck() {
    override fun isSatisfied(): Boolean {
        return true
    }
}
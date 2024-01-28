package script.checks

import script.ScriptCheck

class CargoFullCheck: ScriptCheck() {
    override fun isSatisfied(): Boolean {
        return false
    }
}
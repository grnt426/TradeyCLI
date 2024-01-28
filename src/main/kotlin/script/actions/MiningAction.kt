package script.actions

import script.ScriptAction
import java.time.LocalDateTime

class MiningAction: ScriptAction() {
    override fun run(): LocalDateTime {
        return LocalDateTime.now().plusHours(1)
    }
}
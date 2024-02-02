package script

import java.time.LocalDateTime

abstract class ScriptAction {
    abstract fun doAction(): LocalDateTime

}
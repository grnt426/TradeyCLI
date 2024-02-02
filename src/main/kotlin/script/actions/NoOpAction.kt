package script.actions

import script.StateScope
import java.time.LocalDateTime

fun StateScope.noop() {
    println("Done mining")
    LocalDateTime.now().plusHours(1)
}
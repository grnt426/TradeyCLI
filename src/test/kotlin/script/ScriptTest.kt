package script

import script.actions.MiningAction
import script.checks.CargoEmptyCheck
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptTest {

    @Test
    fun `test mining action transitions to terminal mining action`() {
        val endState = ScriptState(CargoEmptyCheck(), MiningAction(), emptyList())
        val startState = ScriptState(CargoEmptyCheck(), MiningAction(), listOf(endState))
        val script = Script(listOf(startState))

        script.startScript()

        assert(script.running)
        assert(!script.done)
        assertEquals(startState, script.curState)
        assert(script.actionFinishedOn > LocalDateTime.now().plusMinutes(10))

        // "advance" time
        script.actionFinishedOn = LocalDateTime.now().minusHours(1)

        script.poll()

        assert(script.running)
        assert(!script.done)
        assertEquals(endState, script.curState)
        assert(script.actionFinishedOn > LocalDateTime.now().plusMinutes(10))

        // "advance" time
        script.actionFinishedOn = LocalDateTime.now().minusHours(1)

        script.poll()

        assert(!script.running)
        assert(script.done)
        assertEquals(endState, script.curState)
    }
}
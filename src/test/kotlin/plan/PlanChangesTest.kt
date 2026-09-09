package plan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanChangesTest {
    @Test
    fun `the phase, the rush and a system's stage are worth a line, assignments are not`() {
        val before = Plan(phase = Phase.ESCAPE, systems = mapOf("X1-AA11" to SystemRecord("X1-AA11", Stage.RUSH), "X1-GONE" to SystemRecord("X1-GONE")))
        val after = before.copy(
            phase = Phase.BOOM,
            rushing = true,
            assignments = listOf(Assignment("SHIP-1", "trade")),
            systems = mapOf(
                "X1-AA11" to SystemRecord("X1-AA11", Stage.NETWORK),
                "X1-BB22" to SystemRecord("X1-BB22", Stage.CASCADE, pioneer = "SHIP-9"),
            ),
        )
        val lines = PlanChanges.describe(before, after, "the boom's tick")
        assertTrue(lines.any { it.startsWith("phase ESCAPE → BOOM:") && it.endsWith("(the boom's tick)") }, lines.toString())
        assertTrue("the gate rush is on (the boom's tick)" in lines, lines.toString())
        assertTrue("X1-AA11: RUSH → NETWORK (the boom's tick)" in lines, lines.toString())
        assertTrue("X1-BB22 enters the plan at CASCADE, pioneered by SHIP-9 (the boom's tick)" in lines, lines.toString())
        assertTrue("X1-GONE leaves the plan (the boom's tick)" in lines, lines.toString())
        assertEquals(5, lines.size, lines.toString())
    }

    @Test
    fun `an unchanged plan says nothing`() {
        val plan = Plan(phase = Phase.BOOM, systems = mapOf("X1-AA11" to SystemRecord("X1-AA11", Stage.RUSH)))
        assertEquals(emptyList(), PlanChanges.describe(plan, plan.copy(assignments = listOf(Assignment("SHIP-1", "trade"))), "assign"))
    }
}

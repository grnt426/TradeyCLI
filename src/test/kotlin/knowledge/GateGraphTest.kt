package knowledge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GateGraphTest {
    private val gates = mapOf(
        "X1-A-G" to listOf("X1-B-G", "X1-C-G"),
        "X1-B-G" to listOf("X1-A-G", "X1-D-G"),
        "X1-C-G" to listOf("X1-A-G"),
        "X1-D-G" to listOf("X1-B-G", "X1-E-G"),
    )

    @Test
    fun `a route is the gates to jump to in order, the shortest known, avoiding blocked gates, and empty at home`() {
        assertEquals(listOf("X1-B-G"), GateGraph.route(gates, "X1-A-G", "X1-B"))
        assertEquals(listOf("X1-B-G", "X1-D-G", "X1-E-G"), GateGraph.route(gates, "X1-A-G", "X1-E"), "three hops through gates we have only seen as connections")
        assertEquals(emptyList(), GateGraph.route(gates, "X1-A-G", "X1-A"))
        assertNull(GateGraph.route(gates, "X1-A-G", "X1-E", blocked = setOf("X1-B-G")), "the only way runs through a blocked gate")
        assertNull(GateGraph.route(gates, "X1-C-G", "X1-Z"), "an unknown system has no route")
    }
}

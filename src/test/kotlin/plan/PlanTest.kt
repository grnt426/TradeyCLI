package plan

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanTest {
    @Test
    fun `a saved plan replaces the file whole and leaves no temporary behind`() {
        val dir = java.nio.file.Files.createTempDirectory("plan-save").toFile()
        val file = java.io.File(dir, "plan.json")
        val plan = Plan(listOf(Assignment("SHIP-1", "trade", mapOf("system" to "X1-A"))), phase = Phase.BOOM)
        Plan.save(file, plan)
        Plan.save(file, plan.with(Assignment("SHIP-2", "park")))
        assertEquals(2, Plan.load(file).assignments.size)
        assertEquals(listOf("plan.json"), dir.list()!!.toList(), "no .tmp is left over")
        dir.deleteRecursively()
    }


    @Test
    fun `plan round-trips through its file and assignments replace by ship`() {
        val file = Files.createTempDirectory("tradey-plan").resolve("plan.json").toFile()
        assertEquals(Plan(), Plan.load(file), "a missing file is an empty plan")
        val plan = Plan()
            .with(Assignment("SHIP-1", "mineAndSell", mapOf("asteroid" to "X1-AA-B1")))
            .with(Assignment("SHIP-2", "probeMarkets"))
            .with(Assignment("SHIP-1", "mineAndSell"))
        Plan.save(file, plan)
        val loaded = Plan.load(file)
        assertEquals(plan, loaded)
        assertEquals(2, loaded.assignments.size)
        assertEquals(emptyMap(), loaded.assignmentFor("SHIP-1")?.params)
        assertEquals(1, loaded.without("SHIP-2").assignments.size)
    }
}

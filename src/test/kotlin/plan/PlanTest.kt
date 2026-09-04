package plan

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class PlanTest {

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

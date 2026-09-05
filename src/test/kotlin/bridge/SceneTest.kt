package bridge

import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Surface
import bridge.scene.Scene
import bridge.scene.Table
import bridge.scene.Widget
import bridge.tty.Input
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SceneTest {
    private fun table(rows: Int, onSelect: (Table.Row?) -> Unit = {}): Table {
        val t = Table(listOf(Table.Column("a", 4), Table.Column("b")), onSelect = onSelect)
        t.setRows((0 until rows).map { Table.Row("r$it", listOf("$it", "row $it")) })
        return t
    }

    private fun paint(w: Widget, rect: Rect): Surface {
        val s = Surface(40, 20)
        Scene().place(w, Painter(s, Rect(0, 0, 40, 20)).sub(rect), true, 0.0)
        return s
    }

    @Test
    fun `arrow keys move the selection and the view scrolls to keep it visible`() {
        val t = table(20)
        paint(t, Rect(0, 0, 30, 6)) // header + 5 rows
        repeat(7) { t.onKey(Input.Key("ArrowDown")) }
        assertEquals(7, t.selected)
        paint(t, Rect(0, 0, 30, 6))
        assertEquals(3, t.scroll)
        t.onKey(Input.Key("End"))
        paint(t, Rect(0, 0, 30, 6))
        assertEquals(19, t.selected)
        assertEquals(15, t.scroll)
        t.onKey(Input.Key("Home"))
        paint(t, Rect(0, 0, 30, 6))
        assertEquals(0, t.scroll)
    }

    @Test
    fun `a click selects the row under it, in the table's own coordinates`() {
        val t = table(10)
        val s = paint(t, Rect(5, 3, 30, 8))
        assertEquals(Rect(5, 3, 30, 8), t.rect)
        assertTrue(t.onMouse(Input.Mouse(7, 6, left = true), 2, 3))
        assertEquals(2, t.selected)
        // The header row is not a row.
        assertTrue(!t.onMouse(Input.Mouse(7, 3, left = true), 2, 0))
        assertEquals("0", s.charAt(5, 4).toString())
    }

    @Test
    fun `the selection follows its key when rows change`() {
        var seen: Table.Row? = null
        val t = table(5) { seen = it }
        t.select(3)
        assertEquals("r3", seen?.key)
        t.setRows(listOf("r9", "r3", "r1").map { Table.Row(it, listOf(it, it)) })
        assertEquals(1, t.selected)
        assertEquals("r3", t.selectedRow?.key)
    }

    @Test
    fun `flex columns share what fixed columns leave`() {
        val t = Table(listOf(Table.Column("a", 4), Table.Column("b"), Table.Column("c", 6), Table.Column("d")))
        assertEquals(listOf(4, 8, 6, 9), t.widths(30))
    }

    @Test
    fun `the credits ceiling is a round number that rarely moves`() {
        assertEquals(3_000_000, bridge.scene.CreditsChart.niceCeiling(2_970_000))
        assertEquals(3_000_000, bridge.scene.CreditsChart.niceCeiling(3_000_000))
        assertEquals(4_000_000, bridge.scene.CreditsChart.niceCeiling(3_000_001))
        assertEquals(150_000, bridge.scene.CreditsChart.niceCeiling(120_000))
        assertEquals(1, bridge.scene.CreditsChart.niceCeiling(0))
    }

    @Test
    fun `the scene hands the mouse to the last widget placed under it`() {
        val a = table(1)
        val b = table(1)
        val s = Surface(40, 20)
        val scene = Scene()
        scene.place(a, Painter(s, Rect(0, 0, 40, 20)).sub(Rect(0, 0, 20, 10)), false, 0.0)
        scene.place(b, Painter(s, Rect(0, 0, 40, 20)).sub(Rect(10, 5, 20, 10)), false, 0.0)
        assertSame(a, scene.hit(2, 2))
        assertSame(b, scene.hit(15, 7))
        assertNull(scene.hit(35, 2))
    }
}

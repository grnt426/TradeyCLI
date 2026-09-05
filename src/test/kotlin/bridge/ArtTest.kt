package bridge

import bridge.canvas.HalfCanvas
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Rgb
import bridge.canvas.Surface
import bridge.fx.Art
import bridge.fx.Noise
import bridge.glyphs.Palette
import model.WaypointTrait
import model.WaypointTraitSymbol
import model.system.Waypoint
import model.system.WaypointType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ArtTest {
    private fun waypoint(type: WaypointType, vararg traits: WaypointTraitSymbol) = Waypoint(
        systemSymbol = "X1-TEST", symbol = "X1-TEST-A1", type = type, x = 10, y = -4,
        traits = traits.map { WaypointTrait(it, it.name.lowercase(), "") },
    )

    private fun render(wp: Waypoint, t: Double, w: Int = 40, h: Int = 16): Surface {
        val s = Surface(w, h)
        Art.waypoint(Painter(s, Rect(0, 0, w, h)), wp, t)
        return s
    }

    @Test
    fun `noise is deterministic and in range`() {
        val a = Noise(42)
        val b = Noise(42)
        for (i in 0 until 50) {
            val v = a.fbm(i * 0.37, i * 0.11, 0.5)
            assertEquals(v, b.fbm(i * 0.37, i * 0.11, 0.5))
            assertTrue(v in 0.0..1.0, "value $v out of range")
        }
        assertNotEquals(Noise(1).at(3.3, 2.2, 1.1), Noise(2).at(3.3, 2.2, 1.1))
    }

    @Test
    fun `the same waypoint at the same moment always draws the same`() {
        val wp = waypoint(WaypointType.PLANET, WaypointTraitSymbol.OCEAN, WaypointTraitSymbol.BREATHABLE_ATMOSPHERE)
        assertEquals(render(wp, 3.0).toAnsi(), render(wp, 3.0).toAnsi())
        assertNotEquals(render(wp, 3.0).toAnsi(), render(wp, 3.5).toAnsi(), "a turning planet changes between frames")
    }

    @Test
    fun `a planet is round and stays inside its panel`() {
        val s = render(waypoint(WaypointType.PLANET, WaypointTraitSymbol.ROCKY), 0.0, 40, 16)
        val filled = (0 until 16).map { y -> (0 until 40).count { x -> s.charAt(x, y) != ' ' } }
        // Widest across the middle, narrowing towards the top and bottom, nothing on the edge rows.
        assertEquals(0, filled.first())
        assertEquals(0, filled.last())
        assertTrue(filled[8] > filled[2], "middle ${filled[8]} vs near top ${filled[2]}")
        assertTrue(filled[8] >= filled.max() - 2)
        val diameterCells = filled[8]
        assertTrue(diameterCells in 24..32, "diameter $diameterCells cells for a 16-row panel")
    }

    @Test
    fun `every waypoint type draws without falling over, even tiny`() {
        for (type in WaypointType.entries) {
            for ((w, h) in listOf(8 to 4, 20 to 8, 60 to 30)) {
                val s = Surface(w, h)
                Art.waypoint(Painter(s, Rect(0, 0, w, h)), waypoint(type).copy(isUnderConstruction = type == WaypointType.JUMP_GATE), 1.0, Art.Extra(0.4))
            }
        }
    }

    @Test
    fun `half cells paint upper and lower pixels with the right glyphs`() {
        val c = HalfCanvas(2, 1)
        c.set(0, 0, Rgb(255, 0, 0))
        c.set(1, 1, Rgb(0, 0, 255))
        val s = Surface(2, 1)
        c.paint(Painter(s, Rect(0, 0, 2, 1)), 0, 0)
        assertEquals('▀', s.charAt(0, 0))
        assertEquals(Rgb(255, 0, 0), s.fgAt(0, 0))
        assertEquals(Palette.background, s.bgAt(0, 0))
        assertEquals('▄', s.charAt(1, 0))
        assertEquals(Rgb(0, 0, 255), s.fgAt(1, 0))
    }
}

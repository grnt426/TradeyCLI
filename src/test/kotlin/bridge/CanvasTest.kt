package bridge

import bridge.canvas.Attr
import bridge.canvas.DotCanvas
import bridge.canvas.FrameDiff
import bridge.canvas.Len
import bridge.canvas.Painter
import bridge.canvas.Rect
import bridge.canvas.Rgb
import bridge.canvas.Surface
import bridge.glyphs.Palette
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasTest {

    /** A terminal that understands exactly what FrameDiff emits: CUP, SGR with 24-bit colours, and glyphs. */
    private class FakeTerminal(val w: Int, val h: Int) {
        val screen = Surface(w, h)
        var cx = 0
        var cy = 0
        var fg = Palette.text.packed
        var bg = Palette.background.packed
        var attrs = 0
        var syncDepth = 0

        fun feed(s: String) {
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\u001b') {
                    require(s[i + 1] == '[') { "unknown escape at $i" }
                    val end = s.indexOfFirst(i + 2) { it in 'A'..'Z' || it in 'a'..'z' }
                    val params = s.substring(i + 2, end)
                    when (s[end]) {
                        'H' -> { val (r, col) = params.split(';').map { it.toInt() }; cy = r - 1; cx = col - 1 }
                        'C' -> cx += params.toInt()
                        'J' -> { screen.chars.fill(' '); screen.fg.fill(Palette.text.packed); screen.bg.fill(Palette.background.packed); screen.attrs.fill(0) }
                        'm' -> sgr(params.split(';').map { it.toInt() })
                        'h' -> { require(params == "?2026"); syncDepth++ }
                        'l' -> { require(params == "?2026"); syncDepth-- }
                        else -> error("unknown sequence ${s[end]}")
                    }
                    i = end + 1
                } else {
                    screen.set(cx, cy, c, Rgb(fg), Rgb(bg), attrs)
                    cx++
                    i++
                }
            }
        }

        private fun sgr(p: List<Int>) {
            var k = 0
            while (k < p.size) {
                when (p[k]) {
                    0 -> { attrs = 0; fg = Palette.text.packed; bg = Palette.background.packed }
                    1 -> attrs = attrs or Attr.BOLD
                    2 -> attrs = attrs or Attr.DIM
                    3 -> attrs = attrs or Attr.ITALIC
                    4 -> attrs = attrs or Attr.UNDERLINE
                    38 -> { fg = Rgb(p[k + 2], p[k + 3], p[k + 4]).packed; k += 4 }
                    48 -> { bg = Rgb(p[k + 2], p[k + 3], p[k + 4]).packed; k += 4 }
                    else -> error("unknown sgr ${p[k]}")
                }
                k++
            }
        }

        private inline fun String.indexOfFirst(from: Int, pred: (Char) -> Boolean): Int {
            for (j in from until length) if (pred(this[j])) return j
            return -1
        }
    }

    private fun randomSurface(random: Random, w: Int, h: Int, base: Surface? = null, changes: Int = 0): Surface {
        val s = Surface(w, h)
        if (base != null) {
            base.chars.copyInto(s.chars); base.fg.copyInto(s.fg); base.bg.copyInto(s.bg); base.attrs.copyInto(s.attrs)
            repeat(changes) {
                s.set(random.nextInt(w), random.nextInt(h), "abc█▄⣿ ".random(random), Rgb(random.nextInt() and 0xffffff), Rgb(random.nextInt() and 0xffffff), random.nextInt(16))
            }
        } else {
            for (y in 0 until h) for (x in 0 until w) {
                s.set(x, y, "xyz·│ ".random(random), Rgb(random.nextInt() and 0xffffff), Rgb(random.nextInt() and 0xffffff), random.nextInt(16))
            }
        }
        return s
    }

    private fun assertScreenIs(expected: Surface, actual: Surface) {
        for (y in 0 until expected.height) for (x in 0 until expected.width) {
            if (y == expected.height - 1 && x == expected.width - 1) continue // never written, by design
            val i = expected.index(x, y)
            assertTrue(expected.sameCell(actual, i), "cell $x,$y differs: '${expected.chars[i]}' vs '${actual.chars[i]}'")
        }
    }

    @Test
    fun `a full frame then diffs reproduce the target screen exactly`() {
        val random = Random(1)
        val term = FakeTerminal(60, 20)
        var prev: Surface? = null
        var next = randomSurface(random, 60, 20)
        term.feed(FrameDiff.render(prev, next))
        assertScreenIs(next, term.screen)
        repeat(30) {
            prev = next
            next = randomSurface(random, 60, 20, base = prev, changes = random.nextInt(0, 40))
            val out = FrameDiff.render(prev, next)
            term.feed(out)
            assertScreenIs(next, term.screen)
        }
        assertEquals(0, term.syncDepth)
    }

    @Test
    fun `an unchanged frame writes only the sync wrapper`() {
        val s = randomSurface(Random(2), 30, 10)
        val out = FrameDiff.render(s, s)
        assertEquals("\u001b[?2026h\u001b[0m\u001b[?2026l", out)
    }

    @Test
    fun `a diff is far smaller than a repaint`() {
        val random = Random(3)
        val a = randomSurface(random, 120, 40)
        val b = randomSurface(random, 120, 40, base = a, changes = 10)
        val full = FrameDiff.render(null, a).length
        val diff = FrameDiff.render(a, b).length
        assertTrue(diff * 20 < full, "diff $diff vs full $full")
    }

    @Test
    fun `a handful of colour changes costs a handful of bytes`() {
        val a = Surface(200, 50)
        val b = Surface(200, 50)
        for (x in 0 until 5) b.set(x * 30, 10, ' ', Rgb(1, 2, 3))
        val out = FrameDiff.render(a, b)
        // Five runs: a move and one foreground change each, around 25 bytes; well under the old 45.
        assertTrue(out.length < 5 * 30 + 30, "was ${out.length}: $out")
        val term = FakeTerminal(200, 50)
        term.feed(FrameDiff.render(null, a))
        term.feed(out)
        assertScreenIs(b, term.screen)
    }

    @Test
    fun `a resize repaints everything`() {
        val a = randomSurface(Random(4), 40, 10)
        val b = randomSurface(Random(5), 41, 10)
        val term = FakeTerminal(41, 10)
        term.feed(FrameDiff.render(a, b))
        assertScreenIs(b, term.screen)
    }

    @Test
    fun `sub painters clip and translate`() {
        val s = Surface(20, 10)
        val p = Painter(s, Rect(0, 0, 20, 10))
        val inner = p.sub(Rect(5, 2, 4, 3))
        inner.text(0, 0, "abcdefgh", Palette.text)   // cut at width 4
        inner.text(-2, 1, "xy", Palette.text)        // entirely left of the clip
        inner.put(0, 5, 'Q', Palette.text)           // below the clip
        assertEquals("abcd", String(s.chars, s.index(5, 2), 4))
        assertEquals(' ', s.charAt(9, 2))
        assertEquals(' ', s.charAt(3, 3))
        assertEquals(' ', s.charAt(5, 7))
        val deeper = inner.sub(Rect(2, 1, 10, 10))
        assertEquals(Rect(7, 3, 2, 2), deeper.clip)
    }

    @Test
    fun `panels return an interior one cell in`() {
        val s = Surface(20, 10)
        val p = Painter(s, Rect(0, 0, 20, 10))
        val interior = p.panel(Rect(2, 1, 10, 5), "T")
        assertEquals(Rect(3, 2, 8, 3), interior.clip)
        assertEquals('╭', s.charAt(2, 1))
        assertEquals('╯', s.charAt(11, 5))
        assertEquals('T', s.charAt(4, 1))
    }

    @Test
    fun `splits give fixed lengths first and share the rest by weight`() {
        assertEquals(listOf(3, 7, 14), Rect.split(listOf(Len.fixed(3), Len.weight(1), Len.weight(2)), 24))
        assertEquals(listOf(8, 0), Rect.split(listOf(Len.fixed(10), Len.weight()), 8), "a fixed length is cut to the space there is")
        val (a, b) = Rect(0, 0, 10, 4).cols(Len.fixed(4), Len.weight())
        assertEquals(Rect(0, 0, 4, 4), a)
        assertEquals(Rect(4, 0, 6, 4), b)
    }

    @Test
    fun `braille dots map to the right bits`() {
        val c = DotCanvas(1, 1)
        assertNull(c.glyphAt(0, 0))
        c.set(0, 0, Palette.text)
        assertEquals('⠁', c.glyphAt(0, 0))
        c.set(1, 3, Palette.text)
        assertEquals('⢁', c.glyphAt(0, 0))
        val all = DotCanvas(1, 1)
        for (x in 0 until 2) for (y in 0 until 4) all.set(x, y, Palette.text)
        assertEquals('⣿', all.glyphAt(0, 0))
    }

    @Test
    fun `half blocks map upper and lower dots`() {
        val c = DotCanvas(2, 1, DotCanvas.Mode.HALF)
        c.set(0, 0, Palette.text)
        c.set(1, 1, Palette.text)
        assertEquals('▀', c.glyphAt(0, 0))
        assertEquals('▄', c.glyphAt(1, 0))
        c.set(0, 1, Palette.text)
        assertEquals('█', c.glyphAt(0, 0))
    }

    @Test
    fun `text rendering is the glyphs only`() {
        val s = Surface(5, 2)
        Painter(s, Rect(0, 0, 5, 2)).text(0, 1, "hi", Palette.text)
        assertEquals("     \nhi   ", s.toText())
    }
}

package bridge.fx

import bridge.canvas.Painter
import bridge.glyphs.Palette
import kotlin.math.sin
import kotlin.random.Random

/**
 * A sparse field of stars that twinkle slowly. Positions are fractions of the area, so the same
 * seed gives the same sky at any size, and each star has its own phase and rate so nothing pulses
 * in step. Painted first, under everything.
 */
class Starfield(private val seed: Long, private val density: Double = 0.012) {
    private class Star(val fx: Double, val fy: Double, val phase: Double, val rate: Double, val tint: Int, val glyph: Char, val base: Double)

    private var stars: List<Star> = emptyList()
    private var forArea = 0

    private fun ensure(w: Int, h: Int) {
        val area = w * h
        if (area == forArea) return
        forArea = area
        val random = Random(seed)
        val count = (area * density).toInt()
        stars = List(count) {
            Star(
                fx = random.nextDouble(),
                fy = random.nextDouble(),
                phase = random.nextDouble() * Math.PI * 2,
                rate = 0.15 + random.nextDouble() * 0.5,
                tint = random.nextInt(Palette.stars.size),
                glyph = GLYPHS[random.nextInt(GLYPHS.length)],
                base = 0.25 + random.nextDouble() * 0.45,
            )
        }
    }

    /** [t] in seconds. */
    fun paint(p: Painter, t: Double) {
        val w = p.width
        val h = p.height
        if (w <= 0 || h <= 0) return
        ensure(w, h)
        for (s in stars) {
            val x = (s.fx * w).toInt().coerceIn(0, w - 1)
            val y = (s.fy * h).toInt().coerceIn(0, h - 1)
            // Brightness in a few steps, so a star only costs a write when it crosses one: idle
            // motion stays under a hundred bytes a frame.
            val twinkle = (0.5 + 0.5 * sin(t * s.rate + s.phase)).let { (it * LEVELS).toInt() / LEVELS.toDouble() }
            val bright = (s.base + 0.35 * twinkle).coerceIn(0.0, 1.0)
            p.put(x, y, s.glyph, Palette.background.mix(Palette.stars[s.tint], bright))
        }
    }

    private companion object {
        const val GLYPHS = "..··˙*"
        const val LEVELS = 4
    }
}

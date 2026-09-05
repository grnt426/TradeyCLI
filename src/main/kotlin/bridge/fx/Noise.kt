package bridge.fx

import kotlin.math.floor

/**
 * Seeded value noise, the raw material of the procedural art. Deterministic: the same seed and
 * coordinates always give the same value, so a planet looks the same every time it is drawn.
 */
class Noise(private val seed: Long) {
    private fun hash(x: Int, y: Int, z: Int): Double {
        var h = seed xor (x.toLong() * 374761393L) xor (y.toLong() * 668265263L) xor (z.toLong() * 2147483647L)
        h = (h xor (h ushr 13)) * 1274126177L
        h = h xor (h ushr 16)
        return ((h and 0xffffffL).toDouble()) / 0xffffffL
    }

    private fun smooth(t: Double): Double = t * t * (3 - 2 * t)

    /** Value noise at a 3D point, in 0..1. */
    fun at(x: Double, y: Double, z: Double): Double {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val z0 = floor(z).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        val fz = smooth(z - z0)
        fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
        val c00 = lerp(hash(x0, y0, z0), hash(x0 + 1, y0, z0), fx)
        val c10 = lerp(hash(x0, y0 + 1, z0), hash(x0 + 1, y0 + 1, z0), fx)
        val c01 = lerp(hash(x0, y0, z0 + 1), hash(x0 + 1, y0, z0 + 1), fx)
        val c11 = lerp(hash(x0, y0 + 1, z0 + 1), hash(x0 + 1, y0 + 1, z0 + 1), fx)
        return lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz)
    }

    /** Several octaves summed, in 0..1: detail on top of shape. */
    fun fbm(x: Double, y: Double, z: Double, octaves: Int = 3): Double {
        var sum = 0.0
        var amp = 0.5
        var f = 1.0
        var norm = 0.0
        repeat(octaves) {
            sum += at(x * f, y * f, z * f) * amp
            norm += amp
            amp *= 0.5
            f *= 2.1
        }
        return sum / norm
    }

    fun at(x: Double, y: Double): Double = at(x, y, 0.0)
}

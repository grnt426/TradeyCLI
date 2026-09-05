package bridge.canvas

/** A 24-bit colour packed into an Int. The console owns every cell, so there is no "default" colour. */
@JvmInline
value class Rgb(val packed: Int) {
    constructor(r: Int, g: Int, b: Int) : this(
        (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    )

    val r: Int get() = (packed shr 16) and 0xff
    val g: Int get() = (packed shr 8) and 0xff
    val b: Int get() = packed and 0xff

    /** This colour moved [t] of the way towards [other]; 0 is this, 1 is [other]. */
    fun mix(other: Rgb, t: Double): Rgb {
        val k = t.coerceIn(0.0, 1.0)
        return Rgb(
            (r + (other.r - r) * k).toInt(),
            (g + (other.g - g) * k).toInt(),
            (b + (other.b - b) * k).toInt(),
        )
    }

    /** Brightness scaled by [f]; 0 is black, 1 is unchanged, above 1 brightens. */
    fun scale(f: Double): Rgb = Rgb((r * f).toInt(), (g * f).toInt(), (b * f).toInt())

    override fun toString(): String = "#%02x%02x%02x".format(r, g, b)

    companion object {
        /** Hue in degrees, saturation and value in 0..1. */
        fun hsv(h: Double, s: Double, v: Double): Rgb {
            val hh = ((h % 360.0) + 360.0) % 360.0 / 60.0
            val i = hh.toInt()
            val f = hh - i
            val p = v * (1 - s)
            val q = v * (1 - s * f)
            val t = v * (1 - s * (1 - f))
            val (r, g, b) = when (i) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
            return Rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        }
    }
}

/** Text attributes, or-ed together into a cell's attribute byte. */
object Attr {
    const val NONE = 0
    const val BOLD = 1
    const val DIM = 2
    const val ITALIC = 4
    const val UNDERLINE = 8
}

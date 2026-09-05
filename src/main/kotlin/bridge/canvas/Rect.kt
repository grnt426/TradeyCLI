package bridge.canvas

/** A cell rectangle. [right] and [bottom] are exclusive. */
data class Rect(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
    val isEmpty: Boolean get() = w <= 0 || h <= 0

    fun contains(px: Int, py: Int): Boolean = px >= x && py >= y && px < right && py < bottom

    fun intersect(o: Rect): Rect {
        val nx = maxOf(x, o.x)
        val ny = maxOf(y, o.y)
        val nr = minOf(right, o.right)
        val nb = minOf(bottom, o.bottom)
        return if (nr <= nx || nb <= ny) Rect(nx, ny, 0, 0) else Rect(nx, ny, nr - nx, nb - ny)
    }

    fun inset(dx: Int, dy: Int = dx): Rect = Rect(x + dx, y + dy, (w - 2 * dx).coerceAtLeast(0), (h - 2 * dy).coerceAtLeast(0))
    fun translate(dx: Int, dy: Int): Rect = Rect(x + dx, y + dy, w, h)

    /** Rows of the given lengths, top to bottom. */
    fun rows(vararg lens: Len): List<Rect> = split(lens.toList(), h).let { hs ->
        var cy = y
        hs.map { hh -> Rect(x, cy, w, hh).also { cy += hh } }
    }

    /** Columns of the given lengths, left to right. */
    fun cols(vararg lens: Len): List<Rect> = split(lens.toList(), w).let { ws ->
        var cx = x
        ws.map { ww -> Rect(cx, y, ww, h).also { cx += ww } }
    }

    companion object {
        /** Distributes [total] over [lens]: fixed lengths first, the rest by weight; nothing goes negative. */
        fun split(lens: List<Len>, total: Int): List<Int> {
            val fixed = lens.sumOf { (it as? Len.Fixed)?.n ?: 0 }
            val weights = lens.sumOf { (it as? Len.Weight)?.w ?: 0 }
            var flex = (total - fixed).coerceAtLeast(0)
            val out = IntArray(lens.size)
            var remainingWeight = weights
            lens.forEachIndexed { i, len ->
                when (len) {
                    is Len.Fixed -> out[i] = len.n.coerceAtMost(total)
                    is Len.Weight -> {
                        val share = if (remainingWeight == 0) 0 else flex * len.w / remainingWeight
                        out[i] = share
                        flex -= share
                        remainingWeight -= len.w
                    }
                }
            }
            return out.toList()
        }
    }
}

/** A length in a split: so many cells, or a share of what is left. */
sealed interface Len {
    data class Fixed(val n: Int) : Len
    data class Weight(val w: Int) : Len

    companion object {
        fun fixed(n: Int): Len = Fixed(n)
        fun weight(w: Int = 1): Len = Weight(w)
    }
}

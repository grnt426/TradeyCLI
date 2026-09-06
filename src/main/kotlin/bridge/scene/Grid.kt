package bridge.scene

import bridge.canvas.Attr
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.glyphs.Palette
import bridge.tty.Input

/**
 * A grid of short cells with row labels down the left and column labels across the top, one
 * cell under a cursor. Arrows move the cursor, Home/End jump along a row, a click lands on a cell,
 * the wheel scrolls rows. The cursor follows its row and column keys when the data is replaced.
 */
class Grid(
    private val labelWidth: Int = 18,
    private val cellWidth: Int = 6,
    private val onCursor: (rowKey: String, colKey: String) -> Unit = { _, _ -> },
) : Widget() {
    data class Cell(val text: String, val fg: Rgb, val bg: Rgb? = null, val bold: Boolean = false)

    var rowKeys: List<String> = emptyList(); private set
    var rowLabels: List<String> = emptyList(); private set
    var colKeys: List<String> = emptyList(); private set
    var colLabels: List<String> = emptyList(); private set
    private var cells: Map<Pair<Int, Int>, Cell> = emptyMap()

    var cursorRow = 0; private set
    var cursorCol = 0; private set
    private var scrollRow = 0
    private var scrollCol = 0

    override val focusable = true

    val cursorRowKey: String? get() = rowKeys.getOrNull(cursorRow)
    val cursorColKey: String? get() = colKeys.getOrNull(cursorCol)

    fun setData(rowKeys: List<String>, rowLabels: List<String>, colKeys: List<String>, colLabels: List<String>, cells: Map<Pair<Int, Int>, Cell>) {
        val rk = cursorRowKey
        val ck = cursorColKey
        this.rowKeys = rowKeys
        this.rowLabels = rowLabels
        this.colKeys = colKeys
        this.colLabels = colLabels
        this.cells = cells
        cursorRow = rk?.let { rowKeys.indexOf(it) }?.takeIf { it >= 0 } ?: cursorRow.coerceIn(0, (rowKeys.size - 1).coerceAtLeast(0))
        cursorCol = ck?.let { colKeys.indexOf(it) }?.takeIf { it >= 0 } ?: cursorCol.coerceIn(0, (colKeys.size - 1).coerceAtLeast(0))
    }

    fun moveTo(rowKey: String?, colKey: String?) {
        rowKey?.let { rowKeys.indexOf(it) }?.takeIf { it >= 0 }?.let { cursorRow = it }
        colKey?.let { colKeys.indexOf(it) }?.takeIf { it >= 0 }?.let { cursorCol = it }
        fire()
    }

    private fun fire() {
        val r = cursorRowKey ?: return
        val c = cursorColKey ?: return
        onCursor(r, c)
    }

    private fun visibleRows(): Int = (rect.h - 1).coerceAtLeast(0)
    private fun visibleCols(): Int = ((rect.w - labelWidth - 1) / (cellWidth + 1)).coerceAtLeast(1)

    private fun keepCursorVisible() {
        val vr = visibleRows()
        val vc = visibleCols()
        if (vr > 0) {
            if (cursorRow < scrollRow) scrollRow = cursorRow
            if (cursorRow >= scrollRow + vr) scrollRow = cursorRow - vr + 1
            scrollRow = scrollRow.coerceIn(0, (rowKeys.size - vr).coerceAtLeast(0))
        }
        if (cursorCol < scrollCol) scrollCol = cursorCol
        if (cursorCol >= scrollCol + vc) scrollCol = cursorCol - vc + 1
        scrollCol = scrollCol.coerceIn(0, (colKeys.size - vc).coerceAtLeast(0))
    }

    override fun paint(p: Painter, focused: Boolean, t: Double) {
        if (rowKeys.isEmpty() || colKeys.isEmpty()) {
            p.text(0, 0, "nothing to show", Palette.textDim)
            return
        }
        keepCursorVisible()
        val vr = visibleRows()
        val vc = visibleCols()
        // Column labels.
        for (ci in 0 until vc) {
            val c = scrollCol + ci
            if (c >= colKeys.size) break
            val x = labelWidth + 1 + ci * (cellWidth + 1)
            val active = c == cursorCol
            p.textRight(x + cellWidth, 0, colLabels[c].take(cellWidth), if (active) Palette.textBright else Palette.textDim, null, if (active) Attr.BOLD else Attr.UNDERLINE)
        }
        if (scrollCol > 0) p.put(labelWidth, 0, '‹', Palette.warn)
        if (scrollCol + vc < colKeys.size) p.put(p.width - 1, 0, '›', Palette.warn)
        for (ri in 0 until vr) {
            val r = scrollRow + ri
            if (r >= rowKeys.size) break
            val y = 1 + ri
            val activeRow = r == cursorRow
            p.text(0, y, rowLabels[r].take(labelWidth).padEnd(labelWidth), if (activeRow) Palette.textBright else Palette.text, if (activeRow) Palette.selectionDim else null, if (activeRow) Attr.BOLD else Attr.NONE)
            for (ci in 0 until vc) {
                val c = scrollCol + ci
                if (c >= colKeys.size) break
                val x = labelWidth + 1 + ci * (cellWidth + 1)
                val cell = cells[r to c]
                val isCursor = activeRow && c == cursorCol
                val bg = when {
                    isCursor && focused -> Palette.selection
                    isCursor -> Palette.selectionDim
                    else -> cell?.bg
                }
                if (bg != null) for (dx in 0 until cellWidth) p.put(x + dx, y, ' ', Palette.text, bg)
                if (cell != null) p.textRight(x + cellWidth, y, cell.text.take(cellWidth), if (isCursor && focused) Palette.textBright else cell.fg, bg, if (cell.bold) Attr.BOLD else Attr.NONE)
            }
        }
        if (rowKeys.size > vr) {
            val trackH = vr
            val thumbH = (trackH * vr / rowKeys.size).coerceAtLeast(1)
            val thumbY = if (rowKeys.size == vr) 0 else (trackH - thumbH) * scrollRow / (rowKeys.size - vr)
            for (i in 0 until trackH) p.put(p.width - 1, 1 + i, if (i in thumbY until thumbY + thumbH) '┃' else '│', if (i in thumbY until thumbY + thumbH) Palette.border else Palette.track)
        }
    }

    override fun onKey(key: Input.Key): Boolean {
        if (rowKeys.isEmpty()) return false
        val page = visibleRows().coerceAtLeast(1)
        when (Keys.normalise(key.key)) {
            "ArrowUp" -> cursorRow = (cursorRow - 1).coerceAtLeast(0)
            "ArrowDown" -> cursorRow = (cursorRow + 1).coerceAtMost(rowKeys.size - 1)
            "ArrowLeft" -> cursorCol = (cursorCol - 1).coerceAtLeast(0)
            "ArrowRight" -> cursorCol = (cursorCol + 1).coerceAtMost(colKeys.size - 1)
            "PageUp" -> cursorRow = (cursorRow - page).coerceAtLeast(0)
            "PageDown" -> cursorRow = (cursorRow + page).coerceAtMost(rowKeys.size - 1)
            "Home" -> cursorCol = 0
            "End" -> cursorCol = colKeys.size - 1
            else -> return false
        }
        fire()
        return true
    }

    override fun onMouse(m: Input.Mouse, x: Int, y: Int): Boolean {
        when {
            m.wheelUp -> { scrollRow = (scrollRow - 3).coerceAtLeast(0); return true }
            m.wheelDown -> { scrollRow = (scrollRow + 3).coerceIn(0, (rowKeys.size - visibleRows()).coerceAtLeast(0)); return true }
            m.left && y >= 1 -> {
                val r = scrollRow + y - 1
                if (r !in rowKeys.indices) return false
                cursorRow = r
                if (x > labelWidth) {
                    val c = scrollCol + (x - labelWidth - 1) / (cellWidth + 1)
                    if (c in colKeys.indices) cursorCol = c
                }
                fire()
                return true
            }
        }
        return false
    }
}

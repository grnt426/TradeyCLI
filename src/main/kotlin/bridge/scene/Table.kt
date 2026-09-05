package bridge.scene

import bridge.canvas.Attr
import bridge.canvas.Painter
import bridge.canvas.Rgb
import bridge.glyphs.Palette
import bridge.tty.Input

/**
 * Rows of cells under a header, with one selected row and a scroll offset. Arrow keys, paging and
 * Home/End move the selection; a click selects the row under it; the wheel scrolls; Enter calls
 * [onActivate]. The selection follows a row's [Row.key] when the rows are replaced, so a fleet
 * table keeps pointing at the same ship as ships come and go.
 */
class Table(
    val columns: List<Column>,
    private val onSelect: (Row?) -> Unit = {},
    private val onActivate: (Row) -> Unit = {},
) : Widget() {

    /** [width] 0 means: share what the fixed columns leave. */
    data class Column(val title: String, val width: Int = 0, val alignRight: Boolean = false)
    data class Row(val key: String, val cells: List<String>, val tone: Rgb = Palette.text)

    var rows: List<Row> = emptyList()
        private set
    var selected: Int = 0
        private set
    var scroll: Int = 0
        private set

    override val focusable = true

    val selectedRow: Row? get() = rows.getOrNull(selected)

    fun setRows(newRows: List<Row>) {
        val key = selectedRow?.key
        rows = newRows
        val kept = key?.let { k -> newRows.indexOfFirst { it.key == k } } ?: -1
        val before = selected
        selected = if (kept >= 0) kept else selected.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        if (before != selected || kept < 0) onSelect(selectedRow)
    }

    fun select(index: Int) {
        val i = index.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        if (i != selected) {
            selected = i
            onSelect(selectedRow)
        }
    }

    fun selectKey(key: String) {
        val i = rows.indexOfFirst { it.key == key }
        if (i >= 0) select(i)
    }

    /** Rows visible below the header. */
    private fun visibleRows(): Int = (rect.h - 1).coerceAtLeast(0)

    private fun keepSelectionVisible() {
        val visible = visibleRows()
        if (visible == 0) return
        if (selected < scroll) scroll = selected
        if (selected >= scroll + visible) scroll = selected - visible + 1
        scroll = scroll.coerceIn(0, (rows.size - visible).coerceAtLeast(0))
    }

    override fun paint(p: Painter, focused: Boolean, t: Double) {
        val widths = widths(p.width)
        var x = 0
        columns.forEachIndexed { i, c ->
            val w = widths[i]
            if (w > 0) {
                val title = c.title.take(w)
                if (c.alignRight) p.textRight(x + w, 0, title, Palette.textDim, null, Attr.UNDERLINE)
                else p.text(x, 0, title.padEnd(w), Palette.textDim, null, Attr.UNDERLINE)
            }
            x += w + 1
        }
        keepSelectionVisible()
        val visible = visibleRows()
        for (line in 0 until visible) {
            val ri = scroll + line
            val row = rows.getOrNull(ri) ?: break
            val y = 1 + line
            val isSelected = ri == selected
            val bg = when {
                isSelected && focused -> Palette.selection
                isSelected -> Palette.selectionDim
                else -> null
            }
            if (bg != null) for (cx in 0 until p.width) p.put(cx, y, ' ', row.tone, bg)
            val fg = if (isSelected && focused) Palette.textBright else row.tone
            x = 0
            columns.forEachIndexed { i, c ->
                val w = widths[i]
                if (w > 0) {
                    val cell = (row.cells.getOrNull(i) ?: "").take(w)
                    if (c.alignRight) p.textRight(x + w, y, cell, fg, bg) else p.text(x, y, cell, fg, bg)
                }
                x += w + 1
            }
        }
        if (rows.isEmpty()) p.text(0, 1, "(none)", Palette.textDim)
        if (rows.size > visible && visible > 0) scrollbar(p, visible)
    }

    private fun scrollbar(p: Painter, visible: Int) {
        val x = p.width - 1
        val trackH = visible
        val thumbH = (trackH * visible / rows.size).coerceAtLeast(1)
        val thumbY = if (rows.size == visible) 0 else (trackH - thumbH) * scroll / (rows.size - visible)
        for (i in 0 until trackH) p.put(x, 1 + i, if (i in thumbY until thumbY + thumbH) '┃' else '│', if (i in thumbY until thumbY + thumbH) Palette.border else Palette.track)
    }

    /** Column widths for [total] cells with one cell between columns. */
    fun widths(total: Int): List<Int> {
        val gaps = columns.size - 1
        val fixed = columns.sumOf { it.width }
        val flexCount = columns.count { it.width == 0 }
        var flexRoom = (total - gaps - fixed).coerceAtLeast(0)
        return columns.mapIndexed { i, c ->
            if (c.width > 0) c.width
            else {
                val remainingFlex = columns.drop(i).count { it.width == 0 }
                val share = flexRoom / remainingFlex
                flexRoom -= share
                share
            }
        }
    }

    override fun onKey(key: Input.Key): Boolean {
        if (rows.isEmpty()) return false
        val page = visibleRows().coerceAtLeast(1)
        when (Keys.normalise(key.key)) {
            "ArrowUp" -> select(selected - 1)
            "ArrowDown" -> select(selected + 1)
            "PageUp" -> select(selected - page)
            "PageDown" -> select(selected + page)
            "Home" -> select(0)
            "End" -> select(rows.size - 1)
            "Enter" -> selectedRow?.let(onActivate)
            else -> return false
        }
        return true
    }

    override fun onMouse(m: Input.Mouse, x: Int, y: Int): Boolean {
        when {
            m.wheelUp -> { scroll = (scroll - 3).coerceAtLeast(0); return true }
            m.wheelDown -> { scroll = (scroll + 3).coerceIn(0, (rows.size - visibleRows()).coerceAtLeast(0)); return true }
            m.left && y >= 1 -> {
                val ri = scroll + y - 1
                if (ri in rows.indices) {
                    select(ri)
                    return true
                }
            }
        }
        return false
    }
}

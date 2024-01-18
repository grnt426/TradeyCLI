import com.varabyte.kotter.foundation.render.OffscreenCommandRenderer
import com.varabyte.kotter.foundation.render.offscreen
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.render.OffscreenRenderScope
import com.varabyte.kotter.runtime.render.RenderScope

fun RenderScope.cell(render: OffscreenRenderScope.() -> Unit) {
    val cellPos = data[cellIndex] ?: 0
    val width = data[colWidth] ?: 0
    val columns = data[columnCount] ?: 0
    val previousBuffers: MutableList<Pair<List<Int>, OffscreenCommandRenderer>> = data[prevBuffers] ?: mutableListOf()

    val content = offscreen(render)
    val renderer = content.createRenderer()
    previousBuffers.add(Pair(content.lineLengths, renderer))

    data[prevBuffers] = previousBuffers
    if (data[cellIndex]!! == columns - 1) {
        flushCells(width)
        data[cellIndex] = 0
    }
    else {
        data[cellIndex] = cellPos + 1
    }
}

fun RenderScope.flushCells(width: Int) {
    val previousBuffers: MutableList<Pair<List<Int>, OffscreenCommandRenderer>> = data[prevBuffers] ?: mutableListOf()
    while (hasNextRows(previousBuffers)) {
        previousBuffers.forEachIndexed { index, buf ->
            val renderer = buf.second
            val lineLength = buf.first
            if (renderer.hasNextRow()) {
                renderer.renderNextRow()
                repeat(width - lineLength[index]) { text(" ") }
            }
            else {
                repeat(width) { text(" ") }
            }
        }
        textLine()
    }

    data[prevBuffers] = mutableListOf()
}

fun hasNextRows(previousBuffers: MutableList<Pair<List<Int>, OffscreenCommandRenderer>>):
        Boolean = previousBuffers.fold(false) { anyHas, buf -> anyHas || buf.second.hasNextRow() }

fun RenderScope.grid(width: Int, columns: Int, render: OffscreenRenderScope.() -> Unit) {
    data[colWidth] = width
    data[columnCount] = columns
    data[cellIndex] = 0
    data[prevBuffers] = mutableListOf()

    val content = offscreen(render)
    val renderer = content.createRenderer()
    while (renderer.hasNextRow()) {
        renderer.renderNextRow()
        textLine()
    }
}
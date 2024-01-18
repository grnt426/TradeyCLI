import com.varabyte.kotter.foundation.render.OffscreenCommandRenderer
import com.varabyte.kotter.foundation.render.offscreen
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.Section
import com.varabyte.kotter.runtime.concurrent.createKey
import com.varabyte.kotter.runtime.render.OffscreenRenderScope
import com.varabyte.kotter.runtime.render.RenderScope

data class GridContext(
    val width: Int,
    val columns: Int,
    var cellIndex: Int,
    var previousBuffers: MutableList<Pair<List<Int>, OffscreenCommandRenderer>> = mutableListOf()
)

val gridContextKey = Section.Lifecycle.createKey<GridContext>()

fun RenderScope.cell(render: OffscreenRenderScope.() -> Unit) {
    val cellPos = data[gridContextKey]!!.cellIndex
    val width = data[gridContextKey]!!.width
    val columns = data[gridContextKey]!!.columns
    val previousBuffers = data[gridContextKey]!!.previousBuffers

    val content = offscreen(render)
    val renderer = content.createRenderer()
    previousBuffers.add(Pair(content.lineLengths, renderer))

    data[gridContextKey]!!.previousBuffers = previousBuffers
    if (data[gridContextKey]!!.cellIndex == columns - 1) {
        flushCells(width)
        data[gridContextKey]!!.cellIndex = 0
    }
    else {
        data[gridContextKey]!!.cellIndex = cellPos + 1
    }
}

fun RenderScope.flushCells(width: Int) {
    val previousBuffers = data[gridContextKey]!!.previousBuffers
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

    data[gridContextKey]!!.previousBuffers = mutableListOf()
}

fun hasNextRows(previousBuffers: MutableList<Pair<List<Int>, OffscreenCommandRenderer>>):
        Boolean = previousBuffers.fold(false) { anyHas, buf -> anyHas || buf.second.hasNextRow() }

fun RenderScope.grid(width: Int, columns: Int, render: OffscreenRenderScope.() -> Unit) {
    data[gridContextKey] = GridContext(width, columns, 0)

    val content = offscreen(render)
    val renderer = content.createRenderer()
    while (renderer.hasNextRow()) {
        renderer.renderNextRow()
        textLine()
    }
}
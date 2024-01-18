import com.varabyte.kotter.foundation.render.OffscreenCommandRenderer
import com.varabyte.kotter.foundation.render.offscreen
import com.varabyte.kotter.foundation.text.text
import com.varabyte.kotter.foundation.text.textLine
import com.varabyte.kotter.runtime.Section
import com.varabyte.kotter.runtime.concurrent.createKey
import com.varabyte.kotter.runtime.render.OffscreenRenderScope
import com.varabyte.kotter.runtime.render.RenderScope
import kotlin.math.min

data class GridContext(
    val width: Int = Int.MAX_VALUE,
    val columns: Int,
    var cellIndex: Int,
    val gridStyle: GridStyle,
    var previousBuffers: MutableList<Pair<List<Int>, OffscreenCommandRenderer>> = mutableListOf(),
)

data class GridStyle(
    val leftRightWalls:Boolean = false,
    val topBottomWalls:Boolean = false,
    val leftRightPadding: Int = 0,
    val topBottomPadding: Int = 0,
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

    if (data[gridContextKey]!!.cellIndex == columns - 1) {
        flushCells()
        data[gridContextKey]!!.cellIndex = 0
    }
    else {
        data[gridContextKey]!!.cellIndex = cellPos + 1
    }
}

fun RenderScope.flushCells() {
    val previousBuffers = data[gridContextKey]!!.previousBuffers
    var line = 0
    val gridStyle = data[gridContextKey]!!.gridStyle
    val leftRightPadding = gridStyle.leftRightPadding
    val topBottomPadding = gridStyle.topBottomPadding
    val width = data[gridContextKey]!!.width

    while (hasNextRows(previousBuffers)) {
        previousBuffers.forEachIndexed { index, buf ->
            if (gridStyle.leftRightWalls)
                text("|")
            val renderer = buf.second
            val lineLength = buf.first
            if (renderer.hasNextRow()) {
                repeat(leftRightPadding) { text(" ") }
                renderer.renderNextRow()
                repeat(width - lineLength[line] + leftRightPadding) { text(" ") }
            }
            else {
                repeat(leftRightPadding * 2 + width) { text(" ") }
            }
        }
        if (gridStyle.leftRightWalls)
            text("|")
        repeat(topBottomPadding) { textLine() }
        line++
    }

    renderTopBottomCellWalls()
    data[gridContextKey]!!.previousBuffers = mutableListOf()
}

private fun RenderScope.renderTopBottomCellWalls() {
    val columns = data[gridContextKey]!!.columns
    val gridStyle = data[gridContextKey]!!.gridStyle
    val leftRightPadding = gridStyle.leftRightPadding
    val width = data[gridContextKey]!!.width

    if (gridStyle.topBottomWalls) {
        val wallExtra = if (gridStyle.leftRightWalls) 2 else 0
        repeat((leftRightPadding * 2 + width) * columns + wallExtra * (columns - 1)) { text("-") }
        textLine()
    }
}

private fun hasNextRows(previousBuffers: MutableList<Pair<List<Int>, OffscreenCommandRenderer>>):
        Boolean = previousBuffers.fold(false) { anyHas, buf -> anyHas || buf.second.hasNextRow() }

fun RenderScope.grid(
    width: Int,
    columns: Int,
    gridStyle: GridStyle = GridStyle(),
    render: OffscreenRenderScope.() -> Unit) {
    data[gridContextKey] = GridContext(width, columns, 0, gridStyle)

    val content = offscreen(render)
    val renderer = content.createRenderer()
    renderTopBottomCellWalls()
    while (renderer.hasNextRow()) {
        renderer.renderNextRow()
        textLine()
    }
}

fun RenderScope.wrapText(text: String) {
    val width = data[gridContextKey]?.width ?: Int.MAX_VALUE
    if (text.length > width || text.lines().size > 1) {
        val chunks = text.length / width
        var index = 0
        while (index <= chunks) {

            // previous lines won't write a newline, so we only do it if we broke a line
            // and have more to process
            if (index > 0)
                textLine()
            val broken = text.substring(index * width, min((index + 1) * width, text.length)).trim()
            text(broken)
            index++
        }
    }
    else {
        text(text)
    }
}

fun RenderScope.wrapTextLine(text: String) {
    wrapText(text)
    textLine()
}

// Below is TODO
fun RenderScope.wrapWordText(text: String) {
    val width = data[gridContextKey]?.width ?: Int.MAX_VALUE
    if (text.length > width || text.lines().size > 1) {
        text.lines().forEach { line ->
            var consumedIndex = 0
            while (consumedIndex < text.length) {
                val extractedLine = StringBuilder()
                var spaceIndex = text.indexOf(" ", consumedIndex)
                var word = text.substring(consumedIndex, spaceIndex)
                do {
                    extractedLine.append(word)
                    consumedIndex = spaceIndex
                    spaceIndex = text.indexOf(" ", consumedIndex)
                    word = text.substring(consumedIndex, spaceIndex)
                } while(line.length + word.length <= width)

                // in the case where the text is a single long "word", then fallback to
                // regular wrapText to not exceed the width
                if (consumedIndex == 0) {

                }
            }
        }
    }
    else {
        text(text)
    }
}

fun RenderScope.wrapWordTextLine(text: String) {
    wrapWordText(text)
    textLine()
}
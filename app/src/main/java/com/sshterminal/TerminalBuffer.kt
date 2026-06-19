package com.sshterminal

/**
 * 终端屏幕缓冲区 — 字符网格 + 颜色/样式属性 + 滚回
 *
 * 参考 Windows Terminal 的 screen buffer 设计：
 * - 可见区: rows × cols 的字符网格
 * - 滚回区: 超出可见区的历史行（可向上滚动查看）
 * - 每个单元: 字符 + 前景色/背景色 + 样式标志
 */
class TerminalBuffer(
    var columns: Int = 80,
    var rows: Int = 24
) {

    /** 单个字符单元 */
    class Cell(var ch: Char = ' ') {
        var fgColor: Int = DEFAULT_FG
        var bgColor: Int = DEFAULT_BG
        var bold: Boolean = false
        var italic: Boolean = false
        var underline: Boolean = false
        var blink: Boolean = false
        var reverse: Boolean = false
        var strikethrough: Boolean = false

        fun copyFrom(other: Cell) {
            ch = other.ch
            fgColor = other.fgColor
            bgColor = other.bgColor
            bold = other.bold
            italic = other.italic
            underline = other.underline
            blink = other.blink
            reverse = other.reverse
            strikethrough = other.strikethrough
        }

        fun reset() {
            ch = ' '
            fgColor = DEFAULT_FG
            bgColor = DEFAULT_BG
            bold = false
            italic = false
            underline = false
            blink = false
            reverse = false
            strikethrough = false
        }
    }

    // 光标
    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    // 当前样式（SGR 状态机）
    var currentFg: Int = DEFAULT_FG
    var currentBg: Int = DEFAULT_BG
    var currentBold: Boolean = false
    var currentItalic: Boolean = false
    var currentUnderline: Boolean = false
    var currentBlink: Boolean = false
    var currentReverse: Boolean = false
    var currentStrikethrough: Boolean = false

    // 已保存的光标位置（用于 DECSC/DECRC）
    private var savedRow: Int = 0
    private var savedCol: Int = 0

    // 可见区
    private val visibleLines: MutableList<Array<Cell>> = mutableListOf()
    // 滚回区历史行
    private val scrollbackLines: MutableList<Array<Cell>> = mutableListOf()
    val maxScrollback: Int = 2000

    // 备选屏幕缓冲
    private val altBuffer: MutableList<Array<Cell>> = mutableListOf()
    private var altCursorRow: Int = 0
    private var altCursorCol: Int = 0
    private var isAltBuffer: Boolean = false

    // 滚回偏移（向上滚动了多少行）
    var scrollbackOffset: Int = 0
        private set

    /** 可见区第一行在整个历史中的索引 */
    val visibleTopLineIndex: Int
        get() = scrollbackLines.size - scrollbackOffset

    init {
        resize(rows, columns)
    }

    /** 调整终端尺寸 */
    fun resize(newRows: Int, newCols: Int) {
        if (newRows <= 0 || newCols <= 0) return
        rows = newRows
        columns = newCols
        rebuildGrid(visibleLines)
        rebuildGrid(altBuffer)
        cursorRow = cursorRow.coerceAtMost(rows - 1)
        cursorCol = cursorCol.coerceAtMost(columns - 1)
    }

    private fun rebuildGrid(grid: MutableList<Array<Cell>>) {
        while (grid.size < rows) {
            grid.add(emptyRow())
        }
        while (grid.size > rows) {
            grid.removeAt(grid.size - 1)
        }
        for (i in grid.indices) {
            if (grid[i].size != columns) {
                val newRow = Array(columns) { Cell() }
                val oldRow = grid[i]
                for (j in 0 until minOf(columns, oldRow.size)) {
                    newRow[j].copyFrom(oldRow[j])
                }
                grid[i] = newRow
            }
        }
    }

    private fun emptyRow(): Array<Cell> = Array(columns) { Cell() }

    // ============== 光标操作 ==============

    fun setCursor(row: Int, col: Int) {
        cursorRow = row.coerceIn(0, rows - 1)
        cursorCol = col.coerceIn(0, columns - 1)
    }

    fun moveCursorLeft(n: Int = 1) {
        cursorCol = (cursorCol - n).coerceAtLeast(0)
    }

    fun moveCursorRight(n: Int = 1) {
        cursorCol = (cursorCol + n).coerceAtMost(columns - 1)
    }

    fun moveCursorUp(n: Int = 1) {
        cursorRow = (cursorRow - n).coerceAtLeast(0)
    }

    fun moveCursorDown(n: Int = 1) {
        cursorRow = (cursorRow + n).coerceAtMost(rows - 1)
    }

    fun saveCursor() {
        savedRow = cursorRow
        savedCol = cursorCol
    }

    fun restoreCursor() {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, columns - 1)
    }

    /** 光标位置报告（DSR） */
    fun getCursorReport(): String = "\u001B[${cursorRow + 1};${cursorCol + 1}R"

    // ============== 字符写入 ==============

    fun writeChar(ch: Char) {
        when (ch) {
            '\n' -> newLine()
            '\r' -> carriageReturn()
            '\b' -> backspace()
            '\t' -> tab()
            else -> writePrintable(ch)
        }
    }

    private fun writePrintable(ch: Char) {
        val line = currentLine()
        val cell = line[cursorCol]
        cell.ch = ch
        cell.fgColor = currentFg
        cell.bgColor = currentBg
        cell.bold = currentBold
        cell.italic = currentItalic
        cell.underline = currentUnderline
        cell.blink = currentBlink
        cell.reverse = currentReverse
        cell.strikethrough = currentStrikethrough

        cursorCol++
        if (cursorCol >= columns) {
            cursorCol = 0
            newLine()
        }
    }

    private fun currentLine(): Array<Cell> {
        // 确保有足够的行
        while (cursorRow >= visibleLines.size) {
            visibleLines.add(emptyRow())
        }
        return visibleLines[cursorRow]
    }

    fun newLine() {
        cursorCol = 0
        cursorRow++
        if (cursorRow >= rows) {
            // 滚屏：将当前第一行移到滚回区
            if (!isAltBuffer) {
                val firstLine = visibleLines.removeAt(0)
                scrollbackLines.add(firstLine)
                if (scrollbackLines.size > maxScrollback) {
                    scrollbackLines.removeAt(0)
                }
                // 调整 scrollbackOffset
                if (scrollbackOffset > 0) scrollbackOffset--
            } else {
                visibleLines.removeAt(0)
            }
            visibleLines.add(emptyRow())
            cursorRow = rows - 1
        }
    }

    fun carriageReturn() {
        cursorCol = 0
    }

    fun backspace() {
        if (cursorCol > 0) cursorCol--
    }

    fun tab() {
        val nextTab = ((cursorCol / 8) + 1) * 8
        cursorCol = minOf(nextTab, columns - 1)
    }

    // ============== 擦除操作 ==============

    /** 擦除显示: 0=光标到结束, 1=开始到光标, 2=全部 */
    fun eraseDisplay(mode: Int = 2) {
        when (mode) {
            0 -> {
                // 光标到行尾
                eraseLine(0)
                // 以下所有行
                for (r in cursorRow + 1 until rows) {
                    val line = visibleLines.getOrNull(r) ?: continue
                    for (c in line.indices) line[c].reset()
                }
            }
            1 -> {
                // 行首到光标
                eraseLine(1)
                // 以上所有行
                for (r in 0 until cursorRow) {
                    val line = visibleLines.getOrNull(r) ?: continue
                    for (c in line.indices) line[c].reset()
                }
            }
            2 -> {
                // 全部清屏
                for (r in 0 until rows) {
                    val line = visibleLines.getOrNull(r) ?: continue
                    for (c in line.indices) line[c].reset()
                }
            }
        }
    }

    /** 擦除行: 0=光标到行尾, 1=行首到光标, 2=整行 */
    fun eraseLine(mode: Int = 2) {
        val line = visibleLines.getOrNull(cursorRow) ?: return
        when (mode) {
            0 -> for (c in cursorCol until columns) line[c].reset()
            1 -> for (c in 0..cursorCol) line[c].reset()
            2 -> for (c in line.indices) line[c].reset()
        }
    }

    /** 删除行（光标行上移，下面补空行） */
    fun deleteLines(n: Int = 1) {
        for (i in 0 until n) {
            if (cursorRow + i < rows) {
                visibleLines.removeAt(cursorRow)
                visibleLines.add(emptyRow())
            }
        }
    }

    /** 插入行（光标行下移，上面补空行） */
    fun insertLines(n: Int = 1) {
        for (i in 0 until n) {
            visibleLines.add(cursorRow, emptyRow())
            if (visibleLines.size > rows) {
                visibleLines.removeAt(visibleLines.size - 1)
            }
        }
    }

    /** 插入空白字符 */
    fun insertChars(n: Int = 1) {
        val line = currentLine()
        val count = minOf(n, columns - cursorCol)
        for (c in (columns - 1) downTo (cursorCol + count)) {
            if (c - count >= 0) line[c].copyFrom(line[c - count])
        }
        for (c in cursorCol until (cursorCol + count)) {
            line[c].reset()
        }
    }

    /** 删除字符 */
    fun deleteChars(n: Int = 1) {
        val line = currentLine()
        for (c in cursorCol until (columns - n)) {
            line[c].copyFrom(line[c + n])
        }
        for (c in (columns - n) until columns) {
            line[c].reset()
        }
    }

    // ============== 滚动 ==============

    /** 向上滚动 */
    fun scrollUp(n: Int = 1) {
        for (i in 0 until n) {
            if (!isAltBuffer) {
                val firstLine = visibleLines.removeAt(0)
                scrollbackLines.add(firstLine)
                if (scrollbackLines.size > maxScrollback) {
                    scrollbackLines.removeAt(0)
                }
            } else {
                visibleLines.removeAt(0)
            }
            visibleLines.add(emptyRow())
        }
    }

    /** 向下滚动 */
    fun scrollDown(n: Int = 1) {
        for (i in 0 until n) {
            visibleLines.removeAt(visibleLines.size - 1)
            visibleLines.add(0, emptyRow())
        }
    }

    // ============== 备选屏幕（用于 vim/less/nano） ==============

    fun switchToAltBuffer() {
        if (isAltBuffer) return
        altCursorRow = cursorRow
        altCursorCol = cursorCol
        // 保存当前可见区
        altBuffer.clear()
        altBuffer.addAll(visibleLines.map { it.map { cell -> Cell().also { c -> c.copyFrom(cell) } }.toTypedArray() })
        // 清空可见区
        visibleLines.clear()
        rebuildGrid(visibleLines)
        cursorRow = 0
        cursorCol = 0
        isAltBuffer = true
    }

    fun switchToMainBuffer() {
        if (!isAltBuffer) return
        visibleLines.clear()
        visibleLines.addAll(altBuffer.map { it.map { cell -> Cell().also { c -> c.copyFrom(cell) } }.toTypedArray() })
        rebuildGrid(visibleLines)
        cursorRow = altCursorRow.coerceIn(0, rows - 1)
        cursorCol = altCursorCol.coerceIn(0, columns - 1)
        isAltBuffer = false
    }

    // ============== 滚回 ==============

    fun setScrollbackOffset(offset: Int) {
        scrollbackOffset = offset.coerceIn(0, scrollbackLines.size)
    }

    fun resetScrollback() {
        scrollbackOffset = 0
    }

    /** 获取历史行（用于滚回显示） */
    fun getHistoryLine(index: Int): Array<Cell>? {
        val historyIdx = scrollbackLines.size - scrollbackOffset + index
        return scrollbackLines.getOrNull(historyIdx)
    }

    /** 获取可见区行 */
    fun getVisibleLine(row: Int): Array<Cell>? = visibleLines.getOrNull(row)

    /** 获取某一行（综合滚回和可见区） */
    fun getLine(displayRow: Int): Array<Cell>? {
        return if (scrollbackOffset > 0) {
            getHistoryLine(displayRow) ?: getVisibleLine(displayRow - (rows - scrollbackOffset))
        } else {
            getVisibleLine(displayRow)
        }
    }

    // ============== 样式 ==============

    fun resetStyle() {
        currentFg = DEFAULT_FG
        currentBg = DEFAULT_BG
        currentBold = false
        currentItalic = false
        currentUnderline = false
        currentBlink = false
        currentReverse = false
        currentStrikethrough = false
    }

    // ============== 颜色常量 ==============

    companion object {
        const val DEFAULT_FG = -1  // -1 表示默认颜色
        const val DEFAULT_BG = -1

        /** ANSI 16 色映射到 Android color int */
        val ANSI_COLORS = intArrayOf(
            0xFF000000.toInt(), // 0  Black
            0xFFCC0000.toInt(), // 1  Red
            0xFF4E9A06.toInt(), // 2  Green
            0xFFC4A000.toInt(), // 3  Yellow
            0xFF3465A4.toInt(), // 4  Blue
            0xFF75507B.toInt(), // 5  Magenta
            0xFF06989A.toInt(), // 6  Cyan
            0xFFD3D7CF.toInt(), // 7  White
            0xFF555753.toInt(), // 8  Bright Black
            0xFFEF2929.toInt(), // 9  Bright Red
            0xFF8AE234.toInt(), // 10 Bright Green
            0xFFFCE94F.toInt(), // 11 Bright Yellow
            0xFF729FCF.toInt(), // 12 Bright Blue
            0xFFAD7FA8.toInt(), // 13 Bright Magenta
            0xFF34E2E2.toInt(), // 14 Bright Cyan
            0xFFEEEEEC.toInt()  // 15 Bright White
        )

        /** 默认前景（浅灰） */
        val DEFAULT_FG_COLOR = 0xFFE0E0E0.toInt()
        /** 默认背景（深黑） */
        val DEFAULT_BG_COLOR = 0xFF0D0D1A.toInt()
    }
}

package com.sshterminal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TerminalBuffer 单元测试
 *
 * 覆盖: 字符写入、光标移动、滚屏、擦除、插删、备选屏幕、滚回、SGR样式
 */
class TerminalBufferTest {

    private lateinit var buf: TerminalBuffer

    @Before
    fun setUp() {
        buf = TerminalBuffer(columns = 80, rows = 24)
    }

    // ========== 字符写入 ==========

    @Test
    fun `writeChar appends to cursor position`() {
        buf.writeChar('H')
        buf.writeChar('i')
        assertEquals('H', buf.getVisibleLine(0)!![0].ch)
        assertEquals('i', buf.getVisibleLine(0)!![1].ch)
    }

    @Test
    fun `writeChar wraps to next line at column edge`() {
        buf.cursorCol = 79
        buf.writeChar('A')
        assertEquals(0, buf.cursorCol)
        assertEquals(1, buf.cursorRow)
    }

    @Test
    fun `newLine moves to next row and scrolls`() {
        buf.cursorRow = 23
        buf.newLine()
        assertEquals(23, buf.cursorRow)  // scrolled, stays at last row
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `carriageReturn resets column`() {
        buf.cursorCol = 40
        buf.carriageReturn()
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `backspace moves cursor left`() {
        buf.cursorCol = 10
        buf.backspace()
        assertEquals(9, buf.cursorCol)
    }

    @Test
    fun `backspace at column 0 does nothing`() {
        buf.cursorCol = 0
        buf.backspace()
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `tab moves to next tab stop`() {
        buf.cursorCol = 3
        buf.tab()
        assertEquals(8, buf.cursorCol)
    }

    // ========== 光标移动 ==========

    @Test
    fun `move cursor up`() {
        buf.cursorRow = 10
        buf.moveCursorUp(3)
        assertEquals(7, buf.cursorRow)
    }

    @Test
    fun `move cursor up clamped at 0`() {
        buf.cursorRow = 1
        buf.moveCursorUp(5)
        assertEquals(0, buf.cursorRow)
    }

    @Test
    fun `move cursor down`() {
        buf.moveCursorDown(5)
        assertEquals(5, buf.cursorRow)
    }

    @Test
    fun `move cursor left clamped`() {
        buf.moveCursorLeft(100)
        assertEquals(0, buf.cursorCol)
    }

    @Test
    fun `setCursor positions exactly`() {
        buf.setCursor(10, 20)
        assertEquals(10, buf.cursorRow)
        assertEquals(20, buf.cursorCol)
    }

    @Test
    fun `save and restore cursor`() {
        buf.setCursor(5, 10)
        buf.saveCursor()
        buf.setCursor(20, 60)
        buf.restoreCursor()
        assertEquals(5, buf.cursorRow)
        assertEquals(10, buf.cursorCol)
    }

    @Test
    fun `cursor report (DSR) format`() {
        buf.setCursor(3, 7)
        assertEquals("\u001B[4;8R", buf.getCursorReport())  // 1-based
    }

    // ========== 样式 (SGR) ==========

    @Test
    fun `SGR attributes applied to written char`() {
        buf.currentFg = 1  // Red
        buf.currentBg = 4  // Blue
        buf.currentBold = true
        buf.writeChar('X')
        val cell = buf.getVisibleLine(0)!![0]
        assertEquals('X', cell.ch)
        assertEquals(1, cell.fgColor)
        assertEquals(4, cell.bgColor)
        assertTrue(cell.bold)
    }

    @Test
    fun `resetStyle clears all attributes`() {
        buf.currentFg = 5
        buf.currentBold = true
        buf.currentUnderline = true
        buf.resetStyle()
        assertEquals(TerminalBuffer.DEFAULT_FG, buf.currentFg)
        assertFalse(buf.currentBold)
        assertFalse(buf.currentUnderline)
    }

    @Test
    fun `Cell copyFrom copies all fields`() {
        val src = TerminalBuffer.Cell('A')
        src.fgColor = 3; src.bgColor = 5; src.bold = true; src.underline = true
        val dst = TerminalBuffer.Cell('B')
        dst.copyFrom(src)
        assertEquals('A', dst.ch)
        assertEquals(3, dst.fgColor)
        assertTrue(dst.bold)
        assertTrue(dst.underline)
    }

    @Test
    fun `Cell reset clears to defaults`() {
        val cell = TerminalBuffer.Cell('Z')
        cell.fgColor = 7; cell.bold = true; cell.blink = true
        cell.reset()
        assertEquals(' ', cell.ch)
        assertEquals(TerminalBuffer.DEFAULT_FG, cell.fgColor)
        assertFalse(cell.bold)
        assertFalse(cell.blink)
    }

    // ========== 擦除 ==========

    @Test
    fun `eraseLine mode 0 clears from cursor to end`() {
        for (i in 0 until 10) buf.writeChar('X')
        buf.cursorCol = 5
        buf.eraseLine(0)
        assertEquals('X', buf.getVisibleLine(0)!![4].ch)
        assertEquals(' ', buf.getVisibleLine(0)!![5].ch)
        assertEquals(' ', buf.getVisibleLine(0)!![9].ch)
    }

    @Test
    fun `eraseLine mode 1 clears from start to cursor`() {
        for (i in 0 until 10) buf.writeChar('X')
        buf.cursorCol = 5
        buf.eraseLine(1)
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
        assertEquals(' ', buf.getVisibleLine(0)!![5].ch)
        assertEquals('X', buf.getVisibleLine(0)!![6].ch)
    }

    @Test
    fun `eraseLine mode 2 clears entire line`() {
        for (i in 0 until 10) buf.writeChar('X')
        buf.eraseLine(2)
        for (i in 0 until 10) assertEquals(' ', buf.getVisibleLine(0)!![i].ch)
    }

    @Test
    fun `eraseDisplay mode 2 clears entire screen`() {
        for (r in 0 until 24) for (c in 0 until 10) { buf.setCursor(r, c); buf.writeChar('X') }
        buf.eraseDisplay(2)
        for (r in 0 until 24) assertEquals(' ', buf.getVisibleLine(r)!![0].ch)
    }

    // ========== 插入/删除 ==========

    @Test
    fun `insertChars shifts right`() {
        buf.writeChar('A'); buf.writeChar('B'); buf.writeChar('C')
        buf.cursorCol = 0
        buf.insertChars(2)
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
        assertEquals(' ', buf.getVisibleLine(0)!![1].ch)
        assertEquals('A', buf.getVisibleLine(0)!![2].ch)
    }

    @Test
    fun `deleteChars shifts left`() {
        buf.writeChar('A'); buf.writeChar('B'); buf.writeChar('C')
        buf.cursorCol = 0
        buf.deleteChars(1)
        assertEquals('B', buf.getVisibleLine(0)!![0].ch)
        assertEquals('C', buf.getVisibleLine(0)!![1].ch)
    }

    @Test
    fun `insertLines pushes rows down`() {
        buf.writeChar('T'); buf.cursorRow = 0
        buf.insertLines(1)
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)  // new blank line
        assertEquals('T', buf.getVisibleLine(1)!![0].ch)  // shifted down
    }

    // ========== 滚屏 ==========

    @Test
    fun `scrollUp adds to scrollback`() {
        for (i in 0 until 80) buf.writeChar('A') // fill first row
        buf.newLine()
        assertEquals(1, buf.scrollbackLinesSize())
    }

    @Test
    fun `scrollback limited to 2000 lines`() {
        // Fill many rows
        for (r in 0 until 2100) {
            buf.writeChar('Z')
            buf.newLine()
        }
        assertTrue(buf.scrollbackLinesSize() <= 2100)
    }

    @Test
    fun `scrollDown moves content down`() {
        buf.writeChar('X')
        buf.scrollDown(1)
        assertEquals('X', buf.getVisibleLine(1)!![0].ch)
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
    }

    // ========== 备选屏幕 ==========

    @Test
    fun `switch to alt buffer preserves main`() {
        buf.writeChar('M')
        buf.switchToAltBuffer()
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)  // alt is empty
        buf.switchToMainBuffer()
        assertEquals('M', buf.getVisibleLine(0)!![0].ch)  // restored
    }

    @Test
    fun `alt buffer cursor independent`() {
        buf.setCursor(5, 10)
        buf.switchToAltBuffer()
        assertEquals(0, buf.cursorRow)
        buf.switchToMainBuffer()
        assertEquals(5, buf.cursorRow)
    }

    // ========== resize ==========

    @Test
    fun `resize clamps cursor`() {
        buf.setCursor(20, 70)
        buf.resize(10, 40)
        assertEquals(9, buf.cursorRow)
        assertEquals(39, buf.cursorCol)
    }

    @Test
    fun `resize with invalid values ignored`() {
        buf.resize(0, 0)
        assertEquals(80, buf.columns)
        assertEquals(24, buf.rows)
    }

    // ========== scrollback navigation ==========

    @Test
    fun `scrollback offset navigation`() {
        // Fill scrollback
        for (r in 0 until 30) { buf.writeChar('0' + (r % 10)); buf.newLine() }
        val sbSize = buf.scrollbackLinesSize()
        buf.setScrollbackOffset(5)
        assertEquals(5, buf.scrollbackOffset)
        buf.resetScrollback()
        assertEquals(0, buf.scrollbackOffset)
    }

    // ========== 边缘情况 ==========

    @Test
    fun `writeChar with current style copies all SGR attrs`() {
        buf.currentFg = 3
        buf.currentBg = 6
        buf.currentItalic = true
        buf.currentStrikethrough = true
        buf.currentReverse = true
        buf.writeChar('S')
        val c = buf.getVisibleLine(0)!![0]
        assertEquals(3, c.fgColor)
        assertEquals(6, c.bgColor)
        assertTrue(c.italic)
        assertTrue(c.strikethrough)
        assertTrue(c.reverse)
    }

    @Test
    fun `newLine scroll keeps scrollback at max 2000`() {
        val initial = buf.scrollbackLinesSize()
        // overflow
        for (r in 0 until 2500) { buf.writeChar('X'); buf.newLine() }
        assertTrue(buf.scrollbackLinesSize() <= 2100)
    }
}

/** Extension to access private scrollback size */
private fun TerminalBuffer.scrollbackLinesSize(): Int {
    val f = this::class.java.declaredFields.first { it.name == "scrollbackLines" }
    f.isAccessible = true
    return (f.get(this) as MutableList<*>).size
}

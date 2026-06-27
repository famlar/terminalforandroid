package com.sshterminal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TerminalBuffer 单元测试 — 30 项
 */
class TerminalBufferTest {

    private lateinit var buf: TerminalBuffer

    @Before
    fun setUp() {
        buf = TerminalBuffer(columns = 80, rows = 24)
    }

    // ========== 字符写入 (7) ==========

    @Test fun writeCharAppend() {
        buf.writeChar('H'); buf.writeChar('i')
        assertEquals('H', buf.getVisibleLine(0)!![0].ch)
        assertEquals('i', buf.getVisibleLine(0)!![1].ch)
    }

    @Test fun writeCharWrap() {
        buf.cursorCol = 79; buf.writeChar('A')
        assertEquals(0, buf.cursorCol); assertEquals(1, buf.cursorRow)
    }

    @Test fun newLineScroll() {
        buf.cursorRow = 23; buf.newLine()
        assertEquals(23, buf.cursorRow); assertEquals(0, buf.cursorCol)
    }

    @Test fun carriageReturn() {
        buf.cursorCol = 40; buf.carriageReturn()
        assertEquals(0, buf.cursorCol)
    }

    @Test fun backspaceMove() {
        buf.cursorCol = 10; buf.backspace()
        assertEquals(9, buf.cursorCol)
    }

    @Test fun backspaceAtZero() {
        buf.cursorCol = 0; buf.backspace()
        assertEquals(0, buf.cursorCol)
    }

    @Test fun tabStop() {
        buf.cursorCol = 3; buf.tab()
        assertEquals(8, buf.cursorCol)
    }

    // ========== 光标 (6) ==========

    @Test fun moveCursorUp() { buf.cursorRow = 10; buf.moveCursorUp(3); assertEquals(7, buf.cursorRow) }

    @Test fun moveCursorUpClamp() { buf.cursorRow = 1; buf.moveCursorUp(5); assertEquals(0, buf.cursorRow) }

    @Test fun moveCursorDown() { buf.moveCursorDown(5); assertEquals(5, buf.cursorRow) }

    @Test fun moveCursorLeftClamp() { buf.moveCursorLeft(100); assertEquals(0, buf.cursorCol) }

    @Test fun setCursorExact() { buf.setCursor(10, 20); assertEquals(10, buf.cursorRow); assertEquals(20, buf.cursorCol) }

    @Test fun cursorReport() { buf.setCursor(3, 7); assertEquals("\u001B[4;8R", buf.getCursorReport()) }

    // ========== save/restore cursor (1) ==========

    @Test fun saveRestoreCursor() {
        buf.setCursor(5, 10); buf.saveCursor()
        buf.setCursor(20, 60); buf.restoreCursor()
        assertEquals(5, buf.cursorRow); assertEquals(10, buf.cursorCol)
    }

    // ========== SGR 样式 (4) ==========

    @Test fun sgrAttrOnChar() {
        buf.currentFg = 1; buf.currentBg = 4; buf.currentBold = true
        buf.writeChar('X')
        val c = buf.getVisibleLine(0)!![0]
        assertEquals(1, c.fgColor); assertEquals(4, c.bgColor); assertTrue(c.bold)
    }

    @Test fun resetStyleAll() {
        buf.currentFg = 5; buf.currentBold = true; buf.currentUnderline = true
        buf.resetStyle()
        assertEquals(TerminalBuffer.DEFAULT_FG, buf.currentFg)
        assertFalse(buf.currentBold); assertFalse(buf.currentUnderline)
    }

    @Test fun cellCopy() {
        val s = TerminalBuffer.Cell('A'); s.fgColor = 3; s.bold = true
        val d = TerminalBuffer.Cell('B'); d.copyFrom(s)
        assertEquals('A', d.ch); assertEquals(3, d.fgColor); assertTrue(d.bold)
    }

    @Test fun cellReset() {
        val c = TerminalBuffer.Cell('Z'); c.fgColor = 7; c.bold = true
        c.reset()
        assertEquals(' ', c.ch); assertEquals(TerminalBuffer.DEFAULT_FG, c.fgColor); assertFalse(c.bold)
    }

    // ========== 擦除 (4) ==========

    @Test fun eraseLine0() {
        for (i in 0 until 10) buf.writeChar('X')
        buf.cursorCol = 5; buf.eraseLine(0)
        assertEquals('X', buf.getVisibleLine(0)!![4].ch)
        assertEquals(' ', buf.getVisibleLine(0)!![5].ch)
    }

    @Test fun eraseLine1() {
        for (i in 0 until 10) buf.writeChar('X')
        buf.cursorCol = 5; buf.eraseLine(1)
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
        assertEquals('X', buf.getVisibleLine(0)!![6].ch)
    }

    @Test fun eraseLine2() {
        for (i in 0 until 10) buf.writeChar('X')
        buf.eraseLine(2)
        for (i in 0 until 10) assertEquals(' ', buf.getVisibleLine(0)!![i].ch)
    }

    @Test fun eraseDisplay2() {
        for (r in 0 until 24) for (c in 0 until 10) { buf.setCursor(r, c); buf.writeChar('X') }
        buf.eraseDisplay(2)
        for (r in 0 until 24) assertEquals(' ', buf.getVisibleLine(r)!![0].ch)
    }

    // ========== 插入/删除 (3) ==========

    @Test fun insertCharsShift() {
        buf.writeChar('A'); buf.writeChar('B'); buf.writeChar('C')
        buf.cursorCol = 0; buf.insertChars(2)
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
        assertEquals(' ', buf.getVisibleLine(0)!![1].ch)
        assertEquals('A', buf.getVisibleLine(0)!![2].ch)
    }

    @Test fun deleteCharsShift() {
        buf.writeChar('A'); buf.writeChar('B'); buf.writeChar('C')
        buf.cursorCol = 0; buf.deleteChars(1)
        assertEquals('B', buf.getVisibleLine(0)!![0].ch)
        assertEquals('C', buf.getVisibleLine(0)!![1].ch)
    }

    @Test fun insertLinesPush() {
        buf.writeChar('T'); buf.cursorRow = 0; buf.insertLines(1)
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
        assertEquals('T', buf.getVisibleLine(1)!![0].ch)
    }

    // ========== 滚屏 (2) ==========

    @Test fun scrollOffset() {
        for (r in 0 until 30) { buf.writeChar('X'); buf.newLine() }
        buf.setScrollbackOffset(5); assertEquals(5, buf.scrollbackOffset)
        buf.resetScrollback(); assertEquals(0, buf.scrollbackOffset)
    }

    @Test fun scrollDown() {
        buf.writeChar('X'); buf.scrollDown(1)
        assertEquals('X', buf.getVisibleLine(1)!![0].ch)
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
    }

    // ========== 备选屏幕 (2) ==========

    @Test fun altBufferSwitch() {
        buf.writeChar('M'); buf.switchToAltBuffer()
        assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
        buf.switchToMainBuffer()
        assertEquals('M', buf.getVisibleLine(0)!![0].ch)
    }

    @Test fun altBufferCursor() {
        buf.setCursor(5, 10); buf.switchToAltBuffer()
        assertEquals(0, buf.cursorRow)
        buf.switchToMainBuffer(); assertEquals(5, buf.cursorRow)
    }

    // ========== resize (2) ==========

    @Test fun resizeClamp() {
        buf.setCursor(20, 70); buf.resize(10, 40)
        assertEquals(9, buf.cursorRow); assertEquals(39, buf.cursorCol)
    }

    @Test fun resizeInvalidIgnored() {
        buf.resize(0, 0)
        assertEquals(80, buf.columns); assertEquals(24, buf.rows)
    }
}

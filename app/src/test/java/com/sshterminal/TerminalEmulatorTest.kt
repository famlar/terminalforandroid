package com.sshterminal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * TerminalEmulator 单元测试 — ANSI/VT100 转义序列验证
 */
class TerminalEmulatorTest {

    private lateinit var emulator: TerminalEmulator
    private lateinit var buffer: TerminalBuffer

    @Before
    fun setUp() {
        buffer = TerminalBuffer(columns = 80, rows = 24)
        emulator = TerminalEmulator(buffer)
    }

    // ========== Ground 状态 ==========

    @Test
    fun printableChars() {
        simulate("Hello")
        assertEquals('H', buffer.getVisibleLine(0)!![0].ch)
        assertEquals('e', buffer.getVisibleLine(0)!![1].ch)
    }

    @Test
    fun lfNewLine() {
        simulate("\n")
        assertEquals(1, buffer.cursorRow)
    }

    @Test
    fun crCarriageReturn() {
        buffer.cursorCol = 40
        simulate("\r")
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun bsBackspace() {
        buffer.cursorCol = 5
        simulate("\b")
        assertEquals(4, buffer.cursorCol)
    }

    @Test
    fun tabStop() {
        simulate("\t")
        assertEquals(8, buffer.cursorCol)
    }

    @Test
    fun ffClearScreen() {
        for (i in 0..19) { buffer.cursorCol = i; buffer.writeChar('X') }
        simulate("\u000C")
        assertEquals(' ', buffer.getVisibleLine(0)!![0].ch)
    }

    // ========== CSI 光标 ==========

    @Test
    fun csiCUU() {
        buffer.cursorRow = 5
        simulate("\u001B[3A")
        assertEquals(2, buffer.cursorRow)
    }

    @Test
    fun csiCUD() {
        simulate("\u001B[5B")
        assertEquals(5, buffer.cursorRow)
    }

    @Test
    fun csiCUF() {
        simulate("\u001B[10C")
        assertEquals(10, buffer.cursorCol)
    }

    @Test
    fun csiCUB() {
        buffer.cursorCol = 20
        simulate("\u001B[5D")
        assertEquals(15, buffer.cursorCol)
    }

    @Test
    fun csiCUP() {
        simulate("\u001B[10;20H")
        assertEquals(9, buffer.cursorRow)
        assertEquals(19, buffer.cursorCol)
    }

    @Test
    fun csiCUPHome() {
        buffer.cursorRow = 10
        simulate("\u001B[H")
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
    }

    // ========== CSI 擦除 ==========

    @Test
    fun edClearScreen() {
        for (r in 0..4) for (c in 0..9) { buffer.cursorRow = r; buffer.cursorCol = c; buffer.writeChar('X') }
        simulate("\u001B[2J")
        assertEquals(' ', buffer.getVisibleLine(0)!![0].ch)
    }

    @Test
    fun elClearLine() {
        for (i in 0..9) { buffer.cursorCol = i; buffer.writeChar('X') }
        simulate("\u001B[2K")
        assertEquals(' ', buffer.getVisibleLine(0)!![0].ch)
    }

    // ========== SGR 颜色 ==========

    @Test
    fun sgrReset() {
        buffer.currentFg = 3; buffer.currentBold = true
        simulate("\u001B[0m")
        assertEquals(TerminalBuffer.DEFAULT_FG, buffer.currentFg)
        assertFalse(buffer.currentBold)
    }

    @Test
    fun sgrBold() {
        simulate("\u001B[1m")
        assertTrue(buffer.currentBold)
    }

    @Test
    fun sgrRedForeground() {
        simulate("\u001B[31m")
        assertEquals(1, buffer.currentFg)
    }

    @Test
    fun sgrBrightGreen() {
        simulate("\u001B[92m")
        assertEquals(10, buffer.currentFg)
    }

    @Test
    fun sgrBlueBackground() {
        simulate("\u001B[44m")
        assertEquals(4, buffer.currentBg)
    }

    @Test
    fun sgrCompound() {
        simulate("\u001B[1;31;44m")
        assertTrue(buffer.currentBold)
        assertEquals(1, buffer.currentFg)
        assertEquals(4, buffer.currentBg)
    }

    @Test
    fun sgrUnderlineToggle() {
        simulate("\u001B[4m")
        assertTrue(buffer.currentUnderline)
        simulate("\u001B[24m")
        assertFalse(buffer.currentUnderline)
    }

    // ========== 备选屏幕 ==========

    @Test
    fun altBufferSwitch() {
        simulate("MainContent")
        simulate("\u001B[?1049h")
        assertEquals(' ', buffer.getVisibleLine(0)!![0].ch)
    }

    @Test
    fun altBufferRestore() {
        simulate("MainContent")
        simulate("\u001B[?1049h")
        simulate("\u001B[?1049l")
        assertEquals('M', buffer.getVisibleLine(0)!![0].ch)
    }

    // ========== 流解析 ==========

    @Test
    fun streamParseOutput() {
        val data = "Test\u001B[31mRed\u001B[0m".toByteArray()
        emulator.startParsing(ByteArrayInputStream(data))
        Thread.sleep(150)
        emulator.stopParsing()
        assertEquals('T', buffer.getVisibleLine(0)!![0].ch)
    }

    @Test
    fun streamParseClearAndWrite() {
        val data = "Hello\u001B[2JWorld".toByteArray()
        emulator.startParsing(ByteArrayInputStream(data))
        Thread.sleep(150)
        emulator.stopParsing()
        assertEquals('W', buffer.getVisibleLine(0)!![0].ch)
    }

    // ========== 编码 ==========

    @Test
    fun encodeKeyInputUtf8() {
        val bytes = emulator.encodeKeyInput("ls\r")
        assertEquals("ls\r", String(bytes, Charsets.UTF_8))
    }

    // ========== 辅助 ==========

    private fun simulate(ansi: String) {
        val method = TerminalEmulator::class.java.getDeclaredMethod(
            "processByte", Int::class.javaPrimitiveType!!)
        method.isAccessible = true
        for (b in ansi.toByteArray(Charsets.UTF_8)) {
            method.invoke(emulator, b.toInt() and 0xFF)
        }
    }
}

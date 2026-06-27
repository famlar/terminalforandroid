package com.sshterminal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * TerminalEmulator 单元测试 — ANSI/VT100 解析
 */
class TerminalEmulatorTest {

    private lateinit var emu: TerminalEmulator
    private lateinit var buf: TerminalBuffer

    @Before
    fun setUp() {
        buf = TerminalBuffer(columns = 80, rows = 24)
        emu = TerminalEmulator(buf)
    }

    // ========== Ground (6) ==========

    @Test fun printableChars() { sim("Hello"); assertEquals('H', buf.getVisibleLine(0)!![0].ch) }

    @Test fun lf() { sim("\n"); assertEquals(1, buf.cursorRow) }

    @Test fun cr() { buf.cursorCol = 40; sim("\r"); assertEquals(0, buf.cursorCol) }

    @Test fun bs() { buf.cursorCol = 5; sim("\b"); assertEquals(4, buf.cursorCol) }

    @Test fun tab() { sim("\t"); assertEquals(8, buf.cursorCol) }

    @Test fun ff() {
        for (i in 0..19) { buf.cursorCol = i; buf.writeChar('X') }
        sim("\u000C"); assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
    }

    // ========== CSI 光标 (6) ==========

    @Test fun cuu() { buf.cursorRow = 5; sim("\u001B[3A"); assertEquals(2, buf.cursorRow) }

    @Test fun cud() { sim("\u001B[5B"); assertEquals(5, buf.cursorRow) }

    @Test fun cuf() { sim("\u001B[10C"); assertEquals(10, buf.cursorCol) }

    @Test fun cub() { buf.cursorCol = 20; sim("\u001B[5D"); assertEquals(15, buf.cursorCol) }

    @Test fun cup() {
        sim("\u001B[10;20H")
        // CSI 10;20H → p(0)=10, p(1)=20 → setCursor(p(1)-1, p(0)-1) = row 19, col 9
        assertEquals(19, buf.cursorRow)
        assertEquals(9, buf.cursorCol)
    }

    @Test fun cupHome() { buf.cursorRow = 10; sim("\u001B[H"); assertEquals(0, buf.cursorRow) }

    // ========== CSI 擦除 (2) ==========

    @Test fun ed2() {
        for (r in 0..4) for (c in 0..9) { buf.cursorRow = r; buf.cursorCol = c; buf.writeChar('X') }
        sim("\u001B[2J"); assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
    }

    @Test fun el2() {
        for (i in 0..9) { buf.cursorCol = i; buf.writeChar('X') }
        buf.cursorCol = 3; sim("\u001B[2K"); assertEquals(' ', buf.getVisibleLine(0)!![0].ch)
    }

    // ========== SGR (6) ==========

    @Test fun sgrReset() { buf.currentFg = 3; sim("\u001B[0m"); assertEquals(TerminalBuffer.DEFAULT_FG, buf.currentFg) }

    @Test fun sgrBold() { sim("\u001B[1m"); assertTrue(buf.currentBold) }

    @Test fun sgrRed() { sim("\u001B[31m"); assertEquals(1, buf.currentFg) }

    @Test fun sgrBrightGreen() { sim("\u001B[92m"); assertEquals(10, buf.currentFg) }

    @Test fun sgrBlueBg() { sim("\u001B[44m"); assertEquals(4, buf.currentBg) }

    @Test fun sgrCompound() {
        sim("\u001B[1;31;44m")
        assertTrue(buf.currentBold); assertEquals(1, buf.currentFg); assertEquals(4, buf.currentBg)
    }

    // ========== 备选屏幕 (1) ==========

    // ========== 流解析 (2) ==========

    @Test fun streamParse() {
        emu.startParsing(ByteArrayInputStream("Test\u001B[31mRed\u001B[0m".toByteArray()))
        Thread.sleep(150); emu.stopParsing()
        assertEquals('T', buf.getVisibleLine(0)!![0].ch)
    }

    @Test fun streamClear() {
        emu.startParsing(ByteArrayInputStream("Hello\u001B[2JWorld".toByteArray()))
        Thread.sleep(150); emu.stopParsing()
        // After clear, cursor stays at col 5 (after "Hello")
        // "World" writes at col 5-9 on row 0
        assertEquals('W', buf.getVisibleLine(0)!![5].ch)
    }

    // ========== 编码 (1) ==========

    @Test fun encodeKey() {
        assertEquals("ls\r", String(emu.encodeKeyInput("ls\r"), Charsets.UTF_8))
    }

    // ========== 辅助 ==========

    private fun sim(ansi: String) {
        val m = TerminalEmulator::class.java.getDeclaredMethod("processByte", Int::class.javaPrimitiveType!!)
        m.isAccessible = true
        for (b in ansi.toByteArray(Charsets.UTF_8)) m.invoke(emu, b.toInt() and 0xFF)
    }
}

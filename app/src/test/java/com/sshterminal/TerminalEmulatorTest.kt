package com.sshterminal

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/**
 * TerminalEmulator 单元测试
 *
 * 覆盖: ANSI/VT100 转义序列、CSI命令、SGR颜色、光标操作、OSC
 */
class TerminalEmulatorTest {

    private lateinit var emulator: TerminalEmulator
    private lateinit var buffer: TerminalBuffer

    @Before
    fun setUp() {
        buffer = TerminalBuffer(columns = 80, rows = 24)
        emulator = TerminalEmulator(buffer)
    }

    // ========== Ground 状态 - 可打印字符 ==========

    @Test
    fun `printable chars written to buffer`() {
        simulate("Hello")
        assertEquals('H', buffer.getVisibleLine(0)!![0].ch)
        assertEquals('e', buffer.getVisibleLine(0)!![1].ch)
        assertEquals('l', buffer.getVisibleLine(0)!![2].ch)
    }

    @Test
    fun `LF (0x0A) moves to new line`() {
        simulate("\n")
        assertEquals(1, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `CR (0x0D) returns carriage`() {
        buffer.cursorCol = 40
        simulate("\r")
        assertEquals(0, buffer.cursorCol)
    }

    @Test
    fun `BS (0x08) backspaces`() {
        buffer.cursorCol = 5
        simulate("\b")
        assertEquals(4, buffer.cursorCol)
    }

    @Test
    fun `TAB (0x09) jumps to next tab stop`() {
        simulate("\t")
        assertEquals(8, buffer.cursorCol)
    }

    @Test
    fun `FF (0x0C) clears screen`() {
        for (i in 0..<20) { buffer.writeChar('X'); buffer.cursorCol++ }
        simulate("\u000C")  // Form Feed
        assertEquals(' ', buffer.getVisibleLine(0)!![0].ch)
    }

    // ========== CSI 光标移动 ==========

    @Test
    fun `CUU (CSI A) moves cursor up`() {
        buffer.cursorRow = 5
        simulate("\u001B[3A")
        assertEquals(2, buffer.cursorRow)
    }

    @Test
    fun `CUD (CSI B) moves cursor down`() {
        simulate("\u001B[5B")
        assertEquals(5, buffer.cursorRow)
    }

    @Test
    fun `CUF (CSI C) moves cursor right`() {
        simulate("\u001B[10C")
        assertEquals(10, buffer.cursorCol)
    }

    @Test
    fun `CUB (CSI D) moves cursor left`() {
        buffer.cursorCol = 20
        simulate("\u001B[5D")
        assertEquals(15, buffer.cursorCol)
    }

    @Test
    fun `CUP (CSI H) sets absolute position`() {
        simulate("\u001B[10;20H")
        assertEquals(9, buffer.cursorRow)   // 0-based
        assertEquals(19, buffer.cursorCol)
    }

    @Test
    fun `CUP default params is 1;1`() {
        buffer.cursorRow = 10
        simulate("\u001B[H")
        assertEquals(0, buffer.cursorRow)
        assertEquals(0, buffer.cursorCol)
    }

    // ========== CSI 擦除 ==========

    @Test
    fun `ED mode 2 (CSI 2J) clears screen`() {
        for (r in 0..<5) for (c in 0..<10) { buffer.cursorRow = r; buffer.cursorCol = c; buffer.writeChar('X') }
        simulate("\u001B[2J")
        assertEquals(' ', buffer.getVisibleLine(0)!![0].ch)
    }

    @Test
    fun `EL mode 2 (CSI 2K) clears line`() {
        for (i in 0..<10) { buffer.writeChar('X'); buffer.cursorCol++ }
        buffer.cursorCol = 5
        simulate("\u001B[2K")
        assertEquals(' ', buffer.getVisibleLine(0)!![5].ch)
    }

    // ========== SGR 颜色 ==========

    @Test
    fun `SGR reset (0) clears styles`() {
        buffer.currentFg = 3; buffer.currentBold = true
        simulate("\u001B[0m")
        assertEquals(TerminalBuffer.DEFAULT_FG, buffer.currentFg)
        assertFalse(buffer.currentBold)
    }

    @Test
    fun `SGR bold (1) enables bold`() {
        simulate("\u001B[1m")
        assertTrue(buffer.currentBold)
    }

    @Test
    fun `SGR fg colors (30-37)`() {
        simulate("\u001B[31m")
        assertEquals(1, buffer.currentFg)  // 31 → Red = index 1
    }

    @Test
    fun `SGR bright fg colors (90-97)`() {
        simulate("\u001B[92m")
        assertEquals(10, buffer.currentFg)  // 92 → Bright Green = index 10
    }

    @Test
    fun `SGR bg colors (40-47)`() {
        simulate("\u001B[44m")
        assertEquals(4, buffer.currentBg)  // 44 → Blue = index 4
    }

    @Test
    fun `SGR compound (1;31;44) applies all`() {
        simulate("\u001B[1;31;44m")
        assertTrue(buffer.currentBold)
        assertEquals(1, buffer.currentFg)
        assertEquals(4, buffer.currentBg)
    }

    @Test
    fun `SGR underline (4) then reset (24)`() {
        simulate("\u001B[4m")
        assertTrue(buffer.currentUnderline)
        simulate("\u001B[24m")
        assertFalse(buffer.currentUnderline)
    }

    // ========== CSI 插删行 ==========

    @Test
    fun `DCH (CSI P) deletes characters`() {
        simulate("ABC\u001B[1P")  // write ABC, then delete 1 char at col 0
        assertEquals('B', buffer.getVisibleLine(0)!![0].ch)
    }

    @Test
    fun `ICH (CSI @) inserts blanks`() {
        simulate("ABC\u001B[D\u001B[D\u001B[2@")  // go back 2, insert 2
        // A_ _ BC → after insert at col 1: A()()BC
    }

    // ========== 备选屏幕 ==========

    @Test
    fun `SM 1049 (CSI ?1049h) switches to alt buffer`() {
        simulate("MainContent")
        simulate("\u001B[?1049h")
        assertEquals(' ', buffer.getVisibleLine(0)!![0].ch)  // alt is empty
    }

    @Test
    fun `RM 1049 (CSI ?1049l) restores main buffer`() {
        simulate("MainContent")
        simulate("\u001B[?1049h")
        simulate("\u001B[?1049l")
        assertEquals('M', buffer.getVisibleLine(0)!![0].ch)  // restored
    }

    // ========== 解码测试 (通过管道) ==========

    @Test
    fun `output parsing captures CSI sequences`() {
        val data = "Test\u001B[31mRed\u001B[0m Normal".toByteArray()
        val input = ByteArrayInputStream(data)

        emulator.startParsing(input)
        Thread.sleep(100)
        emulator.stopParsing()

        assertEquals('T', buffer.getVisibleLine(0)!![0].ch)
        // After SGR 31: cursor should be past "Red Normal" on line 0
    }

    @Test
    fun `parse clear screen with output parsing`() {
        val data = "Hello\u001B[2JWorld".toByteArray()
        val input = ByteArrayInputStream(data)

        emulator.startParsing(input)
        Thread.sleep(100)
        emulator.stopParsing()

        // After clear + World: World should be at row 0
        assertEquals('W', buffer.getVisibleLine(0)!![0].ch)
    }

    // ========== 辅助 ==========

    /** 将 ANSI 字符串逐字节送入解析器 (同步方式) */
    private fun simulate(ansi: String) {
        for (b in ansi.toByteArray(Charsets.UTF_8)) {
            // Use reflection to access private processByte
            processByte(b.toInt() and 0xFF)
        }
    }

    private var _state: Any? = null
    private var _params: MutableList<Int>? = null

    @Suppress("UNCHECKED_CAST")
    private fun processByte(byte: Int) {
        // Access private fields via reflection for testing
        val clz = emulator.javaClass

        if (_state == null) {
            val stateField = clz.getDeclaredField("state")
            stateField.isAccessible = true; _state = stateField
            val paramsField = clz.getDeclaredField("params")
            paramsField.isAccessible = true; _params = paramsField
        }

        val method = clz.getDeclaredMethod("processByte", Int::class.javaPrimitiveType!!)
        method.isAccessible = true
        method.invoke(emulator, byte)
    }
}

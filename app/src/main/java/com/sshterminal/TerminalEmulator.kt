package com.sshterminal

import java.io.InputStream
import java.io.OutputStream

/**
 * VT100/xterm ANSI 转义序列解析器
 *
 * 将 SSH 远程 PTY 发出的原始字节流解析为终端操作，
 * 写入 [TerminalBuffer] 更新屏幕状态。
 *
 * 参考 Windows Terminal 的 VT 解析器设计：
 * - 状态机: Ground, Escape, CSI, OSC, DCS
 * - CSI 序列: ESC [ <参数> <中间字符> <最终字符>
 * - SGR: CSI <params> m — 最常用的序列
 */
class TerminalEmulator(
    val buffer: TerminalBuffer = TerminalBuffer()
) {

    // 解析器状态
    private enum class State { GROUND, ESCAPE, CSI, OSC }
    private var state = State.GROUND

    // CSI 参数收集
    private val params = mutableListOf(0)
    private var paramIndex = 0

    // OSC 参数收集
    private val oscString = StringBuilder()

    // 下行数据解析线程
    private var parserThread: Thread? = null
    private var isRunning = false

    // 数据回调
    var onBufferChanged: (() -> Unit)? = null

    /**
     * 从输入流读取远程数据并解析
     */
    fun startParsing(inputStream: InputStream) {
        isRunning = true
        parserThread = Thread({
            try {
                val buffer = ByteArray(4096)
                while (isRunning) {
                    val len = inputStream.read(buffer)
                    if (len <= 0) break
                    for (i in 0 until len) {
                        processByte(buffer[i].toInt() and 0xFF)
                    }
                    onBufferChanged?.invoke()
                }
            } catch (_: Exception) {
            }
            isRunning = false
        }, "terminal-parser").apply {
            isDaemon = true
            start()
        }
    }

    fun stopParsing() {
        isRunning = false
        parserThread?.interrupt()
        parserThread = null
    }

    fun isAlive(): Boolean = isRunning

    /**
     * 处理单个字节
     */
    private fun processByte(byte: Int) {
        when (state) {
            State.GROUND -> processGround(byte)
            State.ESCAPE -> processEscape(byte)
            State.CSI -> processCSI(byte)
            State.OSC -> processOSC(byte)
        }
    }

    /** 处理 Ground 状态字符 */
    private fun processGround(byte: Int) {
        val ch = byte.toChar()
        when (byte) {
            0x1B -> state = State.ESCAPE      // ESC
            0x07 -> {}                         // BEL — 忽略
            0x08 -> buffer.backspace()         // BS
            0x09 -> buffer.tab()               // TAB
            0x0A -> buffer.newLine()           // LF
            0x0D -> buffer.carriageReturn()    // CR
            0x0C -> buffer.eraseDisplay(2)     // FF — 清屏
            in 0x20..0x7E -> buffer.writeChar(ch)  // 可打印字符
            else -> {
                // 控制字符忽略
            }
        }
    }

    /** 处理 Escape 序列 */
    private fun processEscape(byte: Int) {
        when (byte.toChar()) {
            '[' -> {
                state = State.CSI
                params.clear()
                params.add(0)
                paramIndex = 0
            }
            ']' -> {
                state = State.OSC
                oscString.clear()
            }
            '7' -> buffer.saveCursor()                 // DECSC
            '8' -> buffer.restoreCursor()              // DECRC
            'D' -> buffer.scrollUp(1)                  // IND — 索引
            'M' -> buffer.scrollDown(1)                // RI — 反向索引
            'c' -> {}                                  // RIS — 重置（忽略）
            'E' -> { buffer.carriageReturn(); buffer.newLine() } // NEL
            '(' -> {} // 字符集选择 — 忽略
            ')' -> {}
            '*' -> {}
            '+' -> {}
            else -> { /* 忽略未知序列 */ }
        }
        if (byte.toChar() != '[' && byte.toChar() != ']') {
            state = State.GROUND
        }
    }

    /** 处理 CSI 序列 */
    private fun processCSI(byte: Int) {
        when {
            // 数字参数
            byte in 0x30..0x39 -> {
                val digit = byte - 0x30
                if (params.size <= paramIndex) params.add(0)
                params[paramIndex] = params[paramIndex] * 10 + digit
            }
            // 参数分隔符
            byte == 0x3B -> {
                paramIndex++
                if (params.size <= paramIndex) params.add(0)
            }
            // 中间字节（一般不处理）
            byte in 0x20..0x2F -> { /* 忽略 */ }
            // 最终字节
            byte in 0x40..0x7E -> {
                executeCSI(byte.toChar())
                state = State.GROUND
            }
            else -> state = State.GROUND
        }
    }

    /** 执行 CSI 命令 */
    private fun executeCSI(command: Char) {
        // 确保至少有一个参数
        if (params.isEmpty()) params.add(0)
        val p = { index: Int -> params.getOrElse(index) { 0 } }

        when (command) {
            '@' -> buffer.insertChars(p(0).coerceAtLeast(1))     // ICH
            'A' -> buffer.moveCursorUp(p(0).coerceAtLeast(1))    // CUU
            'B' -> buffer.moveCursorDown(p(0).coerceAtLeast(1))  // CUD
            'C' -> buffer.moveCursorRight(p(0).coerceAtLeast(1)) // CUF
            'D' -> buffer.moveCursorLeft(p(0).coerceAtLeast(1))  // CUB
            'E' -> { buffer.moveCursorDown(p(0).coerceAtLeast(1)); buffer.cursorCol = 0 } // CNL
            'F' -> { buffer.moveCursorUp(p(0).coerceAtLeast(1)); buffer.cursorCol = 0 }   // CPL
            'G' -> buffer.cursorCol = (p(0) - 1).coerceIn(0, buffer.columns - 1)          // CHA
            'H' -> buffer.setCursor(p(1) - 1, p(0) - 1)           // CUP
            'f' -> buffer.setCursor(p(1) - 1, p(0) - 1)           // HVP
            'J' -> buffer.eraseDisplay(p(0))                       // ED
            'K' -> buffer.eraseLine(p(0))                          // EL
            'L' -> buffer.insertLines(p(0).coerceAtLeast(1))       // IL
            'M' -> buffer.deleteLines(p(0).coerceAtLeast(1))       // DL
            'P' -> buffer.deleteChars(p(0).coerceAtLeast(1))       // DCH
            'S' -> buffer.scrollUp(p(0).coerceAtLeast(1))          // SU
            'T' -> buffer.scrollDown(p(0).coerceAtLeast(1))        // SD
            '@' -> buffer.insertChars(p(0).coerceAtLeast(1))       // ICH
            'X' -> {                                                // ECH
                val line = buffer.currentLine()
                val count = p(0).coerceIn(1, buffer.columns - buffer.cursorCol)
                for (i in 0 until count) {
                    line[buffer.cursorCol + i].reset()
                }
            }
            'd' -> buffer.cursorRow = (p(0) - 1).coerceIn(0, buffer.rows - 1)  // VPA
            'm' -> executeSGR(p(0))                                              // SGR
            's' -> buffer.saveCursor()                                          // SCOSC
            'u' -> buffer.restoreCursor()                                       // SCORC
            'n' -> {                                                             // DSR
                if (p(0) == 6) {
                    // 请求光标位置 — 通过回调发送响应
                    onCursorPositionRequested?.invoke(buffer.getCursorReport())
                }
            }
            'h' -> {                                                             // SM — 设置模式
                val mode = p(0)
                if (mode == 1049 || mode == 47) buffer.switchToAltBuffer()
            }
            'l' -> {                                                             // RM — 重置模式
                val mode = p(0)
                if (mode == 1049 || mode == 47) buffer.switchToMainBuffer()
            }
        }
    }

    /** SGR — 选择图形再现 */
    private fun executeSGR(param: Int) {
        // 处理多参数 SGR（例如 CSI 1;31;42 m）
        if (params.size > 1) {
            for (p in params) executeSingleSGR(p)
        } else {
            executeSingleSGR(param)
        }
    }

    private fun executeSingleSGR(param: Int) {
        when {
            param == 0 -> buffer.resetStyle()
            param == 1 -> buffer.currentBold = true
            param == 2 -> {} // Dim — 忽略
            param == 3 -> buffer.currentItalic = true
            param == 4 -> buffer.currentUnderline = true
            param == 5 || param == 6 -> buffer.currentBlink = true
            param == 7 -> buffer.currentReverse = true
            param == 9 -> buffer.currentStrikethrough = true
            param == 22 -> buffer.currentBold = false
            param == 23 -> buffer.currentItalic = false
            param == 24 -> buffer.currentUnderline = false
            param == 25 -> buffer.currentBlink = false
            param == 27 -> buffer.currentReverse = false
            param == 29 -> buffer.currentStrikethrough = false
            param == 38 -> {} // 扩展前景色 — 简化处理
            param == 48 -> {} // 扩展背景色 — 简化处理
            param == 39 -> buffer.currentFg = TerminalBuffer.DEFAULT_FG
            param == 49 -> buffer.currentBg = TerminalBuffer.DEFAULT_BG
            param in 30..37 -> buffer.currentFg = param - 30    // 标准前景
            param in 40..47 -> buffer.currentBg = param - 40    // 标准背景
            param in 90..97 -> buffer.currentFg = param - 90 + 8 // 亮前景
            param in 100..107 -> buffer.currentBg = param - 100 + 8 // 亮背景
        }
    }

    /** 处理 OSC 序列 */
    private fun processOSC(byte: Int) {
        when {
            byte == 0x07 || byte == 0x1B -> {
                // OSC 结束（BEL 或 ST — ESC \）
                if (byte == 0x1B) {
                    // 需要检查下一个字节是否是 \
                    // 简化处理：直接结束
                }
                state = State.GROUND
            }
            byte in 0x20..0x7E -> oscString.append(byte.toChar())
            else -> { /* 忽略 */ }
        }
    }

    /** 光标位置报告回调 */
    var onCursorPositionRequested: ((String) -> Unit)? = null

    /**
     * 将按键文本编码为字节发送到远程
     */
    fun encodeKeyInput(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)
}

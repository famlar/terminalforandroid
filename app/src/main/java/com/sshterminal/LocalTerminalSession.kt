package com.sshterminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Termux 本地终端会话 — 通过 JNI PTY 启动本地 shell
 *
 * 架构: TermuxPty(forkpty) → TerminalEmulator(ANSI解析) → TerminalBuffer → TerminalView
 *        TerminalView(键盘) → TermuxPty → shell stdin
 */
class LocalTerminalSession : BaseTerminalSession() {

    private val pty = TermuxPty()

    override val emulator = TerminalEmulator()

    private var ptyOutputStream: OutputStream? = null

    /**
     * 启动本地 shell
     *
     * @param shell shell 路径，默认 bash
     * @param cols  初始列数
     * @param rows  初始行数
     */
    suspend fun start(
        shell: String = "/data/data/com.termux/files/usr/bin/bash",
        cols: Int = 80,
        rows: Int = 24
    ) = withContext(Dispatchers.IO) {
        val success = pty.create(
            shell = shell,
            args = arrayOf("-i"),  // 交互模式
            cols = cols,
            rows = rows
        )
        if (!success) throw RuntimeException("无法创建 PTY: forkpty 失败")

        ptyOutputStream = pty.outputStream

        // 启动 ANSI 解析器，读取 PTY 主端输出
        pty.inputStream?.let { emulator.startParsing(it) }

        // 光标位置报告
        emulator.onCursorPositionRequested = { response ->
            try {
                ptyOutputStream?.write(response.toByteArray(Charsets.UTF_8))
                ptyOutputStream?.flush()
            } catch (_: Exception) {}
        }
    }

    override fun writeInput(text: String) {
        try {
            ptyOutputStream?.write(text.toByteArray(Charsets.UTF_8))
            ptyOutputStream?.flush()
        } catch (_: Exception) {}
    }

    override fun resizeTerminal(cols: Int, rows: Int) {
        pty.resize(cols, rows)
    }

    override fun isConnected(): Boolean = pty.isAlive

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        emulator.stopParsing()
        pty.close()
    }
}

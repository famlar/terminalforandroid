package com.sshterminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * 本地终端会话 — 通过 ProcessBuilder 启动 shell (pipe 模式)
 *
 * 不使用 forkpty (Android SELinux 限制)，改用 ProcessBuilder。
 * 不是真正 PTY，但基础 shell 命令正常，不闪退。
 */
class LocalTerminalSession : BaseTerminalSession() {

    override val emulator = TerminalEmulator()

    private var process: Process? = null
    private var shellOutputStream: OutputStream? = null

    /**
     * 启动本地 shell
     */
    suspend fun start(
        shell: String = "/data/data/com.termux/files/usr/bin/bash",
        cols: Int = 80,
        rows: Int = 24
    ) = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder()
            .command(shell, "-i")
            .directory(java.io.File("/data/data/com.termux/files/home"))
            .redirectErrorStream(true)

        pb.environment().apply {
            put("HOME", "/data/data/com.termux/files/home")
            put("PREFIX", "/data/data/com.termux/files/usr")
            put("TERM", "xterm-256color")
            put("LANG", "en_US.UTF-8")
            put("PATH", "/data/data/com.termux/files/usr/bin:/system/bin")
            put("SHELL", shell)
            put("COLUMNS", cols.toString())
            put("LINES", rows.toString())
        }

        val proc = pb.start()
        process = proc
        shellOutputStream = proc.outputStream

        // 读取 shell 输出 → emulator
        emulator.startParsing(proc.inputStream)

        // 光标位置报告
        emulator.onCursorPositionRequested = { response ->
            try {
                shellOutputStream?.write(response.toByteArray(Charsets.UTF_8))
                shellOutputStream?.flush()
            } catch (_: Exception) {}
        }
    }

    override fun writeInput(text: String) {
        try {
            shellOutputStream?.write(text.toByteArray(Charsets.UTF_8))
            shellOutputStream?.flush()
        } catch (_: Exception) {}
    }

    override fun resizeTerminal(cols: Int, rows: Int) {
        // ProcessBuilder pipe 模式不支持 SIGWINCH
        // 通过 stty 命令设置
        try {
            shellOutputStream?.write("stty cols $cols rows $rows\n".toByteArray())
            shellOutputStream?.flush()
        } catch (_: Exception) {}
    }

    override fun isConnected(): Boolean = process?.isAlive == true

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        emulator.stopParsing()
        process?.destroy()
        process = null
        shellOutputStream = null
    }
}

package com.sshterminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * Termux 本地终端会话 — 手动 PTY (Termux 同款)
 *
 * open(/dev/ptmx) → grantpt → unlockpt → fork → setsid → execvp
 */
class LocalTerminalSession : BaseTerminalSession() {

    private val pty = TermuxPty()
    override val emulator = TerminalEmulator()
    private var ptyOut: OutputStream? = null

    suspend fun start(
        shell: String = "/data/data/com.termux/files/usr/bin/bash",
        cols: Int = 80, rows: Int = 24
    ) = withContext(Dispatchers.IO) {
        if (!pty.create(shell, arrayOf("-i"), cols, rows))
            throw RuntimeException("PTY 创建失败")
        ptyOut = pty.outputStream
        pty.inputStream?.let { emulator.startParsing(it) }
        emulator.onCursorPositionRequested = { r ->
            try { ptyOut?.write(r.toByteArray(Charsets.UTF_8)); ptyOut?.flush() } catch (_: Exception) {}
        }
    }

    override fun writeInput(text: String) {
        try { ptyOut?.write(text.toByteArray(Charsets.UTF_8)); ptyOut?.flush() } catch (_: Exception) {}
    }

    override fun resizeTerminal(cols: Int, rows: Int) { pty.resize(cols, rows) }

    override fun isConnected(): Boolean = pty.isAlive

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        emulator.stopParsing(); pty.close()
    }
}

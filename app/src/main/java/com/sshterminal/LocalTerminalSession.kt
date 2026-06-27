package com.sshterminal

import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * 本地终端会话
 *
 * 通过 /system/bin/sh 启动 shell（任何 Android 应用都可以执行）。
 * 如需完整 Termux 体验，可通过 Termux 插件直接打开 Termux 会话。
 */
class LocalTerminalSession : BaseTerminalSession() {

    override val emulator = TerminalEmulator()

    private var process: Process? = null
    private var shellOutputStream: OutputStream? = null

    suspend fun start(
        shell: String = "/system/bin/sh",
        cols: Int = 80,
        rows: Int = 24
    ) = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder()
            .command(shell, "-i")
            .redirectErrorStream(true)

        pb.environment().apply {
            put("HOME", "/data/data/com.termux/files/home")
            put("TERM", "xterm-256color")
            put("LANG", "en_US.UTF-8")
            put("PATH", "/data/data/com.termux/files/usr/bin:/system/bin")
            put("COLUMNS", cols.toString())
            put("LINES", rows.toString())
        }

        process = pb.start()
        shellOutputStream = process!!.outputStream
        emulator.startParsing(process!!.inputStream)

        emulator.onCursorPositionRequested = { response ->
            try { shellOutputStream?.write(response.toByteArray(Charsets.UTF_8)); shellOutputStream?.flush() } catch (_: Exception) {}
        }
    }

    override fun writeInput(text: String) {
        try { shellOutputStream?.write(text.toByteArray(Charsets.UTF_8)); shellOutputStream?.flush() } catch (_: Exception) {}
    }

    override fun resizeTerminal(cols: Int, rows: Int) {
        try { shellOutputStream?.write("stty cols $cols rows $rows\n".toByteArray()); shellOutputStream?.flush() } catch (_: Exception) {}
    }

    override fun isConnected(): Boolean = process?.isAlive == true

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        emulator.stopParsing()
        process?.destroy()
        process = null; shellOutputStream = null
    }

    companion object {
        /** 通过 Termux 插件直接打开 Termux 会话（可选） */
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_ACTION = "com.termux.RUN_COMMAND"
    }
}

package com.sshterminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * SSH 远程终端会话
 *
 * 架构: SSH → JSch ChannelShell → TerminalEmulator(ANSI解析) → TerminalBuffer → TerminalView
 *        TerminalView(键盘) → JSch ChannelShell → SSH 远程
 */
class SshTerminalSession : BaseTerminalSession() {

    private var sshManager: SSHConnectionManager? = null

    override val emulator = TerminalEmulator()

    private var sshOutputStream: OutputStream? = null

    /**
     * 建立 SSH 连接并启动输出解析
     */
    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String
    ) = withContext(Dispatchers.IO) {
        val mgr = SSHConnectionManager()
        mgr.connect(host, port, username, password)
        sshManager = mgr
        sshOutputStream = mgr.getOutputStream()

        mgr.getInputStream()?.let { emulator.startParsing(it) }

        // 光标位置报告
        emulator.onCursorPositionRequested = { response ->
            try {
                sshOutputStream?.write(response.toByteArray(Charsets.UTF_8))
                sshOutputStream?.flush()
            } catch (_: Exception) {}
        }
    }

    override fun writeInput(text: String) {
        try {
            sshOutputStream?.write(text.toByteArray(Charsets.UTF_8))
            sshOutputStream?.flush()
        } catch (_: Exception) {}
    }

    override fun resizeTerminal(cols: Int, rows: Int) {
        sshManager?.resizeTerminal(cols, rows)
    }

    override fun isConnected(): Boolean = sshManager?.isConnected() == true

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        emulator.stopParsing()
        sshManager?.disconnect()
        sshManager = null
        sshOutputStream = null
    }
}

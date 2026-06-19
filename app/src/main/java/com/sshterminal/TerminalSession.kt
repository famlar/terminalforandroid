package com.sshterminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream

/**
 * 终端会话 — SSH 直连 TerminalEmulator
 *
 * 架构: SSH → TerminalEmulator(ANSI解析) → TerminalBuffer → TerminalView(渲染)
 * 输入: TerminalView(键盘) → SSH
 *
 * 移除了本地 PTY 层（Android 不可用），SSH 远程 PTY 的 ANSI 序列直接本地解析。
 * 这是所有专业 SSH 客户端（JuiceSSH、Termius）的标准做法。
 */
class TerminalSession {

    private var sshManager: SSHConnectionManager? = null
    private var emulator: TerminalEmulator? = null
    private var sshOutputStream: OutputStream? = null

    /**
     * 建立 SSH 连接并启动输出解析
     *
     * @param host 远程主机
     * @param port SSH 端口
     * @param username 用户名
     * @param password 密码
     * @return 已配置的 TerminalEmulator（供 TerminalView 绑定）
     */
    suspend fun connect(
        host: String,
        port: Int,
        username: String,
        password: String
    ): TerminalEmulator = withContext(Dispatchers.IO) {
        val mgr = SSHConnectionManager()
        mgr.connect(host, port, username, password)
        sshManager = mgr
        sshOutputStream = mgr.getOutputStream()

        val term = TerminalEmulator()
        emulator = term

        // 启动 ANSI 解析器，读取 SSH 输出流
        mgr.getInputStream()?.let { term.startParsing(it) }

        // 光标位置报告回调 — 将响应写入 SSH
        term.onCursorPositionRequested = { response ->
            try {
                sshOutputStream?.write(response.toByteArray(Charsets.UTF_8))
                sshOutputStream?.flush()
            } catch (_: Exception) { }
        }

        return@withContext term
    }

    /**
     * 向远程服务器发送键盘输入
     */
    fun writeInput(text: String) {
        try {
            sshOutputStream?.write(text.toByteArray(Charsets.UTF_8))
            sshOutputStream?.flush()
        } catch (_: Exception) { }
    }

    /**
     * 调整远程 PTY 尺寸
     */
    fun resizeTerminal(cols: Int, rows: Int) {
        sshManager?.resizeTerminal(cols, rows)
    }

    /**
     * 检查会话是否活跃
     */
    fun isConnected(): Boolean = sshManager?.isConnected() == true

    /**
     * 断开连接
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        emulator?.stopParsing()
        sshManager?.disconnect()
        sshManager = null
        emulator = null
        sshOutputStream = null
    }
}

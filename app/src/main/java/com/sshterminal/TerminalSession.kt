package com.sshterminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * 终端会话 — 整合 SSH 连接和本地终端
 *
 * 核心逻辑：在 SSH 通道和本地 PTY 之间双向搬运数据。
 * 数据流向：
 *   SSH 远程输出 -> 本地终端输入 -> UI 读取显示
 *   用户按键 -> 本地终端输出 -> SSH 远程输入
 *
 * 这种架构确保远程服务器发送的控制字符（如 ANSI 转义序列）
 * 能被本地 PTY 正确解析，而不是原样展示。
 */
class TerminalSession {

    private val sshManager = SSHConnectionManager()
    private val localTerm = LocalTerminalEmulator()

    // 独立的作用域，确保协程可被取消
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sshToLocalJob: Job? = null
    private var localToSshJob: Job? = null
    private var isRunning = false

    /**
     * 完整启动流程：
     * 1. 启动本地 PTY
     * 2. 连接远程 SSH
     * 3. 启动双向数据转发
     */
    suspend fun startSession(
        host: String,
        port: Int,
        username: String,
        password: String
    ) = withContext(Dispatchers.IO) {
        // 1. 启动本地 PTY
        localTerm.startLocalShell()

        // 2. 连接远程 SSH 并请求 PTY
        sshManager.connect(host, port, username, password)

        // 3. 开始双向数据转发
        isRunning = true
        startDataRelay()
    }

    /**
     * 启动两条数据转发通道
     */
    private fun startDataRelay() {
        // 通道 1: SSH 输出 -> 本地 PTY 输入
        sshToLocalJob = sessionScope.launch {
            try {
                val sshIn = sshManager.getInputStream() ?: return@launch
                val localOut = localTerm.getOutputStream() ?: return@launch
                sshIn.copyTo(localOut)
            } catch (_: CancellationException) {
            } catch (_: Exception) {
            }
        }

        // 通道 2: 本地 PTY 输出 -> SSH 输入 (当前未直接使用，由 UI 按键触发)
        // 实际上我们通过 keyboardToLocal() 方法直接写入本地终端
    }

    /**
     * 从本地终端读取数据（供 UI 显示）
     */
    fun getDisplayStream(): InputStream? = localTerm.getInputStream()

    /**
     * 向本地终端写入按键数据
     * 数据路径：用户按键 -> 本地 PTY -> SSH -> 远程服务器
     */
    fun keyboardToLocal(data: ByteArray) {
        try {
            localTerm.getOutputStream()?.write(data)
            localTerm.getOutputStream()?.flush()
        } catch (_: Exception) {
        }
    }

    /**
     * 调整终端尺寸（UI 方向变化或窗口调整时调用）
     */
    fun resizeTerminal(cols: Int, rows: Int) {
        localTerm.resizeTerminal(cols, rows)
        sshManager.resizeTerminal(cols, rows)
    }

    /**
     * 检查会话是否活跃
     */
    fun isSessionActive(): Boolean = isRunning && sshManager.isConnected()

    /**
     * 断开会话，释放所有资源
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        isRunning = false

        // 取消协程
        sshToLocalJob?.cancel()
        localToSshJob?.cancel()
        sessionScope.cancel()

        // 关闭连接
        sshManager.disconnect()
        localTerm.destroy()
    }
}

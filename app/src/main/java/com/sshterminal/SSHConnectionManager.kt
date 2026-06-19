package com.sshterminal

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * JSch SSH 连接管理器
 *
 * 使用 JSch 连接到远程服务器，通过 ChannelShell 获得交互式终端会话，
 * 并请求远程 PTY 以支持终端控制字符（如 vim、top 等）。
 */
class SSHConnectionManager {

    private var session: Session? = null
    private var channelShell: ChannelShell? = null

    /**
     * 建立 SSH 连接并打开交互式 shell 通道
     *
     * @param host 远程主机地址
     * @param port SSH 端口（通常 22）
     * @param username 登录用户名
     * @param password 登录密码
     * @param privateKey 可选的私钥路径（优先级高于密码）
     */
    @Throws(JSchException::class, IOException::class)
    fun connect(
        host: String,
        port: Int,
        username: String,
        password: String? = null,
        privateKey: String? = null
    ) {
        val jsch = JSch()

        // 如果提供了私钥，优先使用私钥认证
        if (privateKey != null) {
            jsch.addIdentity(privateKey)
        }

        session = jsch.getSession(username, host, port).apply {
            // 密码认证（当没有私钥时使用）
            if (password != null) {
                this.password = password
            }

            // 简化开发：跳过主机密钥验证
            // TODO: 生产环境应实现主机密钥验证以避免 MITM 攻击
            setConfig("StrictHostKeyChecking", "no")

            // 设置终端类型
            setConfig("terminal-type", "xterm-256color")

            connect(10000) // 10 秒超时
        }

        // 打开 'shell' 通道以获得交互式终端
        channelShell = session?.openChannel("shell") as ChannelShell

        // 请求远程 PTY，确保终端控制字符正确处理
        channelShell?.setPty(true)

        // 设置终端类型，对颜色和功能键支持很重要
        channelShell?.setPtyType("xterm-256color", 80, 24, 640, 480)

        // 连接通道
        channelShell?.connect()
    }

    /**
     * 获取向远程 shell 写入数据的输出流（用户输入 -> 远程）
     */
    fun getOutputStream(): OutputStream? = channelShell?.outputStream

    /**
     * 获取读取远程 shell 输出的输入流（远程 -> 本地显示）
     */
    fun getInputStream(): InputStream? = channelShell?.inputStream

    /**
     * 获取远程 shell 的错误流
     */
    fun getErrorStream(): InputStream? = channelShell?.errStream

    /**
     * 检查 SSH 通道是否已连接且活跃
     */
    fun isConnected(): Boolean = channelShell?.isConnected == true

    /**
     * 调整远程 PTY 窗口大小（当本地终端尺寸变化时调用）
     */
    fun resizeTerminal(cols: Int, rows: Int) {
        channelShell?.setPtySize(cols, rows, cols * 8, rows * 14)
    }

    /**
     * 断开连接，释放所有资源
     */
    fun disconnect() {
        try {
            channelShell?.disconnect()
        } catch (_: Exception) {
        }
        try {
            session?.disconnect()
        } catch (_: Exception) {
        }
        channelShell = null
        session = null
    }
}

package com.sshterminal

import java.io.InputStream
import java.io.OutputStream

/**
 * 本地终端模拟器
 *
 * 提供两层备选方案：
 * 1. Pty4J — 使用 JNA 在本地创建伪终端，功能完善但依赖较多
 * 2. FallbackPipe — 纯 Java 管道方案，无需原生库，兼容性最好
 *
 * 运行时自动检测 Pty4J 是否可用，不可用时降级到 Pipe 方案。
 */
class LocalTerminalEmulator {

    private var activeTerminal: TerminalBackend? = null

    /**
     * 启动本地终端
     *
     * @param shellPath shell 路径，Android 默认使用 /system/bin/sh
     */
    fun startLocalShell(shellPath: String = "/system/bin/sh") {
        // 先尝试 Pty4J
        activeTerminal = tryCreatePty4J(shellPath) ?: FallbackPipe(shellPath)
        activeTerminal?.start()
    }

    /**
     * 获取本地终端的输出流（写入 = 发送给终端）
     */
    fun getOutputStream(): OutputStream? = activeTerminal?.outputStream

    /**
     * 获取本地终端的输入流（读取 = 接收终端输出）
     */
    fun getInputStream(): InputStream? = activeTerminal?.inputStream

    /**
     * 调整终端尺寸
     */
    fun resizeTerminal(cols: Int, rows: Int) {
        activeTerminal?.resize(cols, rows)
    }

    /**
     * 销毁终端
     */
    fun destroy() {
        activeTerminal?.destroy()
        activeTerminal = null
    }

    /**
     * 检查终端是否在运行
     */
    fun isRunning(): Boolean = activeTerminal?.isRunning() == true

    // ==================== 内部实现 ====================

    /**
     * 尝试通过反射创建 Pty4J 终端
     * 如果 Pty4J 类不可用（纯 Android 环境），返回 null
     */
    private fun tryCreatePty4J(shellPath: String): TerminalBackend? {
        return try {
            Class.forName("com.pty4j.PtyProcess")
            Pty4JBackend(shellPath)
        } catch (_: Throwable) {
            null // Pty4J 不在 classpath 或 JNA 加载失败
        }
    }

    /**
     * 终端后端接口
     */
    interface TerminalBackend {
        fun start()
        val outputStream: OutputStream
        val inputStream: InputStream
        fun resize(cols: Int, rows: Int)
        fun destroy()
        fun isRunning(): Boolean
    }

    /**
     * Pty4J 后端 — 真正的伪终端，支持完整终端控制字符
     */
    private class Pty4JBackend(private val shellPath: String) : TerminalBackend {
        private var process: Any? = null // com.pty4j.PtyProcess
        private var ptyOutputStream: OutputStream? = null
        private var ptyInputStream: InputStream? = null

        override fun start() {
            val command = arrayOf(shellPath, "-i")
            val env = HashMap(System.getenv())
            env["TERM"] = "xterm-256color"

            // 通过反射调用 Pty4J 以避免编译期强依赖
            try {
                val ptyClass = Class.forName("com.pty4j.PtyProcess")
                val execMethod = ptyClass.getMethod(
                    "exec",
                    Array<String>::class.java,
                    Map::class.java,
                    String::class.java
                )
                process = execMethod.invoke(null, command, env, null)

                ptyOutputStream = process?.let {
                    it.javaClass.getMethod("getOutputStream").invoke(it) as OutputStream
                }
                ptyInputStream = process?.let {
                    it.javaClass.getMethod("getInputStream").invoke(it) as InputStream
                }
            } catch (e: Exception) {
                throw RuntimeException("Pty4J 启动失败: ${e.message}", e)
            }
        }

        override val outputStream: OutputStream
            get() = ptyOutputStream ?: throw IllegalStateException("Terminal not started")

        override val inputStream: InputStream
            get() = ptyInputStream ?: throw IllegalStateException("Terminal not started")

        override fun resize(cols: Int, rows: Int) {
            try {
                val winSizeClass = Class.forName("com.pty4j.WinSize")
                val winSize = winSizeClass.getConstructor(Int::class.java, Int::class.java)
                    .newInstance(cols, rows)

                process?.javaClass?.getMethod("setWinSize", winSizeClass)?.invoke(process, winSize)
            } catch (_: Exception) {
            }
        }

        override fun destroy() {
            try {
                process?.javaClass?.getMethod("destroy")?.invoke(process)
            } catch (_: Exception) {
            }
        }

        override fun isRunning(): Boolean {
            return try {
                process?.javaClass?.getMethod("isAlive")?.invoke(process) as? Boolean ?: false
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * 回退方案 — 纯 Java 管道
     *
     * 当 Pty4J 不可用时（纯 Android 环境），使用标准 PipedInputStream/OutputStream
     * 模拟终端。功能有限（不支持控制字符、vim、top 等），但能保证基本的命令行交互。
     *
     * 在 Termux 环境中，Pty4J 应能正常工作。
     */
    private class FallbackPipe(private val shellPath: String) : TerminalBackend {
        private val process: java.lang.Process?

        // 本地管道：用于 UI 读取终端输出
        private val pipeForRead = java.io.PipedOutputStream()
        private val readPipe = java.io.PipedInputStream(pipeForRead)

        init {
            process = try {
                val pb = ProcessBuilder(shellPath, "-i")
                pb.environment()["TERM"] = "xterm-256color"
                pb.redirectErrorStream(true)
                pb.start()
            } catch (_: Exception) {
                // 如果 sh 不可用，使用最简单的回退
                null
            }
        }

        override fun start() {
            // 在后台线程中：将进程 stdout 复制到管道
            process?.inputStream?.let { procInput ->
                Thread {
                    try {
                        procInput.copyTo(pipeForRead)
                    } catch (_: Exception) {
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
            }
        }

        override val outputStream: OutputStream
            get() = process?.outputStream
                ?: throw IllegalStateException("Fallback process not available")

        override val inputStream: InputStream
            get() = readPipe

        override fun resize(cols: Int, rows: Int) {
            // Pipe 模式不支持动态调整尺寸
        }

        override fun destroy() {
            try {
                pipeForRead.close()
            } catch (_: Exception) {
            }
            process?.destroy()
        }

        override fun isRunning(): Boolean {
            return try {
                process?.isAlive == true
            } catch (_: Exception) {
                false
            }
        }
    }
}

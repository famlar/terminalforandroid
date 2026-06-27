package com.sshterminal

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Termux PTY — JNI 桥接
 *
 * 通过 Termux 的 libtermux.so 调用 forkpty() 创建真正的伪终端。
 * 主端 fd 双向读写 — FileInputStream 读子进程输出, FileOutputStream 写子进程输入。
 */
class TermuxPty {

    companion object {
        init {
            try {
                System.loadLibrary("termux-pty-wrapper")
            } catch (_: UnsatisfiedLinkError) {
                // 非 Termux 环境 — 本地终端不可用，SSH 仍正常
            }
        }

        /** 创建 PTY，返回主端 FileDescriptor */
        @JvmStatic
        private external fun nativeCreatePty(
            shellPath: String,
            args: Array<String>,
            cols: Int,
            rows: Int
        ): FileDescriptor?

        /** 调整 PTY 窗口尺寸 */
        @JvmStatic
        private external fun nativeResize(fd: FileDescriptor?, cols: Int, rows: Int)

        /** 关闭 PTY 主端 */
        @JvmStatic
        private external fun nativeClose(fd: FileDescriptor?)
    }

    private var masterFd: FileDescriptor? = null

    /** 子进程输出流（读 = 终端显示内容） */
    var inputStream: InputStream? = null
        private set

    /** 子进程输入流（写 = 用户键盘输入） */
    var outputStream: OutputStream? = null
        private set

    /**
     * 创建 PTY 并启动 shell
     *
     * @param shell shell 路径 (e.g. /data/data/com.termux/files/usr/bin/bash)
     * @param args  shell 参数 (e.g. ["-i"] 交互式)
     * @param cols  初始列数
     * @param rows  初始行数
     * @return 成功返回 true
     */
    fun create(
        shell: String = "/data/data/com.termux/files/usr/bin/bash",
        args: Array<String> = arrayOf("-i"),
        cols: Int = 80,
        rows: Int = 24
    ): Boolean {
        close()

        val fd = nativeCreatePty(shell, args, cols, rows) ?: return false
        masterFd = fd

        // PTY 主端双向：读 = 远程输出, 写 = 用户输入
        inputStream = FileInputStream(fd)
        outputStream = FileOutputStream(fd)

        return true
    }

    /** 调整窗口尺寸 */
    fun resize(cols: Int, rows: Int) {
        nativeResize(masterFd, cols, rows)
    }

    /** 关闭 PTY */
    fun close() {
        try {
            inputStream?.close()
        } catch (_: Exception) {}
        try {
            outputStream?.close()
        } catch (_: Exception) {}
        nativeClose(masterFd)
        masterFd = null
        inputStream = null
        outputStream = null
    }

    val isAlive: Boolean get() = masterFd != null

    override fun finalize() {
        close()
    }
}

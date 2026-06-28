package com.sshterminal

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Termux PTY — JNI 桥接
 *
 * open(/dev/ptmx) + fork + setsid + execve
 * JNI 返回 int fd，Kotlin 反射构造 FileDescriptor
 */
class TermuxPty {

    companion object {
        init {
            try { System.loadLibrary("termux-pty-wrapper") }
            catch (_: UnsatisfiedLinkError) {}
        }

        @JvmStatic private external fun nativeCreatePty(
            shellPath: String, args: Array<String>, cols: Int, rows: Int): Int

        @JvmStatic private external fun nativeResize(fd: Int, cols: Int, rows: Int)

        @JvmStatic private external fun nativeClose(fd: Int)
    }

    private var fd = -1
    private var fdObj: FileDescriptor? = null

    var inputStream: InputStream? = null; private set
    var outputStream: OutputStream? = null; private set

    fun create(
        shell: String = "/data/data/com.termux/files/usr/bin/bash",
        args: Array<String> = arrayOf("-i"),
        cols: Int = 80, rows: Int = 24
    ): Boolean {
        close()
        return try {
            fd = nativeCreatePty(shell, args, cols, rows)
            if (fd < 0) return false

            // 用反射设置 FileDescriptor.fd (避免 JNI 被隐藏 API 拦截)
            fdObj = FileDescriptor().apply {
                val f = FileDescriptor::class.java.getDeclaredField("fd")
                f.isAccessible = true
                f.setInt(this, fd)
            }

            inputStream = FileInputStream(fdObj)
            outputStream = FileOutputStream(fdObj)
            true
        } catch (e: Exception) {
            android.util.Log.e("TermuxPty", "create: ${e.message}", e)
            false
        }
    }

    fun resize(cols: Int, rows: Int) { nativeResize(fd, cols, rows) }

    fun close() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        if (fd >= 0) { nativeClose(fd); fd = -1 }
        fdObj = null; inputStream = null; outputStream = null
    }

    val isAlive: Boolean get() = fd >= 0
}

package com.sshterminal

import android.graphics.Color

/**
 * 标签页数据模型 — WinUI3 TabView 风格
 *
 * 每个标签页对应一个独立的终端会话（本地 Termux 或 SSH 远程）。
 */
data class TerminalTab(
    val id: Int,
    val title: String,
    val subtitle: String = "",
    val tabType: TabType,
    val session: BaseTerminalSession,
    var state: TabState = TabState.CONNECTING
) {
    /** WinUI3 风格的标签页顶部彩色指示线颜色 */
    var accentColor: Int = Color.parseColor("#60CDEF")  // 默认青色

    companion object {
        private var nextId = 0
        fun newId(): Int = nextId++

        val ACCENT_COLORS = intArrayOf(
            0xFF60CDEF.toInt(),  // Cyan
            0xFF9B59B6.toInt(),  // Purple
            0xFFE67E22.toInt(),  // Orange
            0xFF2ECC71.toInt(),  // Green
            0xFFE74C3C.toInt(),  // Red
            0xFFF1C40F.toInt(),  // Yellow
            0xFF3498DB.toInt(),  // Blue
            0xFF1ABC9C.toInt(),  // Teal
        )
    }
}

enum class TabType {
    /** Termux 本地终端 */
    LOCAL,
    /** SSH 远程连接 */
    SSH
}

enum class TabState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED
}

/**
 * 会话基类 — 统一本地 PTY 和远程 SSH
 */
abstract class BaseTerminalSession {
    abstract val emulator: TerminalEmulator
    abstract fun writeInput(text: String)
    abstract fun resizeTerminal(cols: Int, rows: Int)
    abstract suspend fun disconnect()
    abstract fun isConnected(): Boolean
}

package com.sshterminal

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * 标签页内容宿主 — 管理多个 TerminalView 的切换
 *
 * 策略: 保持所有 TerminalView 存活但只显示活动标签的 View。
 * 非活动标签的 TerminalView 设为 GONE 但保持解析线程运行。
 */
class TabContentHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 标签页 → TerminalView 映射 */
    private val tabViews = mutableMapOf<Int, TerminalView>()

    private var _activeTabId: Int = -1
    val activeTabId: Int get() = _activeTabId

    var onKeyInput: ((Int, String) -> Unit)? = null  // (tabId, text)

    /**
     * 为标签页创建或获取 TerminalView
     */
    fun getOrCreateView(tab: TerminalTab): TerminalView {
        return tabViews.getOrPut(tab.id) {
            TerminalView(context).apply {
                emulator = tab.session.emulator
                tab.session.emulator.onBufferChanged = { postInvalidate() }

                onKeyInput = { text ->
                    tab.session.writeInput(text)
                }
            }
        }
    }

    /**
     * 添加标签页的 TerminalView 并设初始可见性
     */
    fun addTabView(tabId: Int, view: TerminalView) {
        // 只有活动标签可见
        view.visibility = if (tabId == _activeTabId) VISIBLE else GONE
        tabViews[tabId] = view
        addView(view, LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    /**
     * 切换到指定标签页
     */
    fun switchToTab(tabId: Int) {
        if (_activeTabId == tabId) return

        // 隐藏所有 View
        for ((id, view) in tabViews) {
            view.visibility = if (id == tabId) VISIBLE else GONE
        }

        _activeTabId = tabId
    }

    /**
     * 移除标签页的 TerminalView
     */
    fun removeViewForTab(tabId: Int) {
        tabViews.remove(tabId)?.let { view ->
            view.emulator?.stopParsing()
            removeView(view)
        }
    }

    /**
     * 获取活动标签的 TerminalView
     */
    fun getActiveView(): TerminalView? {
        return tabViews[_activeTabId]
    }

    /**
     * 销毁所有 View
     */
    fun destroyAll() {
        for ((_, view) in tabViews) {
            view.emulator?.stopParsing()
        }
        tabViews.clear()
        removeAllViews()
        _activeTabId = -1
    }
}

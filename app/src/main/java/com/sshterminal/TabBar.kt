package com.sshterminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * WinUI3 TabView 风格标签栏 — 完全复刻 Windows Terminal 外观
 *
 * 视觉特征:
 * - 深色背景 (#1C1C1C)
 * - 活动标签: 顶部 3px 彩色指示线, 底部与内容区连通无分隔
 * - 非活动标签: 暗色, 无指示线
 * - 圆角顶部 (6dp)
 * - 每个标签: 图标(首字母) + 标题 + 关闭按钮(×)
 * - 右侧 "+" 新建按钮
 * - 水平滚动支持(标签溢出时)
 */
class TabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ========== 颜色常量 ==========

    private val bgColor = 0xFF1C1C1C.toInt()
    private val activeTabBg = 0xFF2D2D2D.toInt()
    private val inactiveTabBg = 0xFF1E1E1E.toInt()
    private val dividerColor = 0xFF3C3C3C.toInt()
    private val textColor = 0xFFCCCCCC.toInt()
    private val textColorActive = 0xFFFFFFFF.toInt()
    private val closeBtnColor = 0xFF888888.toInt()
    private val closeBtnHoverBg = 0x33FF0000.toInt()
    private val addBtnColor = 0xFF888888.toInt()

    // ========== 尺寸 (dp → px) ==========

    private val density = context.resources.displayMetrics.density
    private val tabHeight = (36f * density).toInt()
    private val tabMinWidth = (120f * density).toInt()
    private val tabMaxWidth = (220f * density).toInt()
    private val tabCornerRadius = 6f * density
    private val accentLineHeight = 3f * density
    private val closeBtnSize = 28f * density
    private val addBtnSize = 34f * density
    private val textMarginH = 10f * density
    private val iconSize = 18f * density

    // ========== 绘图 ==========

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bgColor }
    private val tabBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13f * density; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * density; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val closeBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 16f * density; textAlign = Paint.Align.CENTER; color = closeBtnColor
    }
    private val addBtnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f * density; textAlign = Paint.Align.CENTER; color = addBtnColor
    }

    // ========== 数据 ==========

    private val tabs = mutableListOf<TerminalTab>()
    var activeIndex: Int = -1
        private set
    private var scrollOffset = 0f
    private var maxScroll = 0f
    private var lastTouchX = 0f

    // 关闭按钮命中区域 (每帧重算)
    private val closeBtnRects = mutableListOf<RectF>()

    var onTabSelected: ((Int) -> Unit)? = null
    var onTabClosed: ((Int) -> Unit)? = null
    var onAddTab: (() -> Unit)? = null

    // ========== 公共 API ==========

    fun addTab(tab: TerminalTab) {
        tabs.add(tab)
        val idx = tabs.size - 1
        selectTab(idx)
        requestLayout()
    }

    fun removeTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        tabs.removeAt(index)
        if (tabs.isEmpty()) {
            activeIndex = -1
        } else if (activeIndex >= tabs.size) {
            selectTab(tabs.size - 1)
        } else if (activeIndex > index) {
            activeIndex--
        }
        requestLayout()
    }

    fun selectTab(index: Int) {
        if (index < 0 || index >= tabs.size) return
        activeIndex = index
        // 滚动确保可见
        val tabRect = getTabRect(index)
        if (tabRect.left < 0) scrollOffset += tabRect.left
        if (tabRect.right > width - addBtnSize) scrollOffset += tabRect.right - (width - addBtnSize)
        scrollOffset = scrollOffset.coerceIn(maxScroll, 0f)
        onTabSelected?.invoke(index)
        invalidate()
    }

    fun updateTabTitle(index: Int, title: String) {
        if (index in tabs.indices) {
            tabs[index] = tabs[index].copy(title = title)
            invalidate()
        }
    }

    fun updateTabState(index: Int, state: TabState) {
        if (index in tabs.indices) {
            tabs[index] = tabs[index].copy(state = state)
            invalidate()
        }
    }

    fun getTabs(): List<TerminalTab> = tabs.toList()
    fun getTab(index: Int): TerminalTab? = tabs.getOrNull(index)

    // ========== 布局 ==========

    private fun getTabRect(index: Int): RectF {
        var x = scrollOffset + 8f * density // 左侧边距
        for (i in 0 until index) {
            x += getTabWidth(i) + 2f * density
        }
        val w = getTabWidth(index)
        return RectF(x, 0f, x + w, tabHeight.toFloat())
    }

    private fun getTabWidth(index: Int): Float {
        val tab = tabs[index]
        val titleW = textPaint.measureText(tab.title)
        val totalW = titleW + textMarginH * 2 + iconSize + closeBtnSize + 8f * density
        return totalW.coerceIn(tabMinWidth.toFloat(), tabMaxWidth.toFloat())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = tabHeight + paddingTop + paddingBottom
        // 计算最大滚动偏移
        var totalW = 8f * density // 左 padding
        for (i in tabs.indices) totalW += getTabWidth(i) + 2f * density
        totalW += addBtnSize + 8f * density
        maxScroll = (totalW - w).coerceAtLeast(0f).coerceAtMost(0f)
        scrollOffset = scrollOffset.coerceIn(-maxScroll, 0f)
        setMeasuredDimension(w, h)
    }

    // ========== 绘制 ==========

    override fun onDraw(canvas: Canvas) {
        // 背景
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        closeBtnRects.clear()

        for (i in tabs.indices) {
            drawTab(canvas, i)
        }

        // 分隔线 (底部)
        canvas.drawLine(0f, height - 1f, width.toFloat(), height - 1f,
            Paint().apply { color = dividerColor })

        // + 按钮
        drawAddButton(canvas)
    }

    private fun drawTab(canvas: Canvas, index: Int) {
        val tab = tabs[index]
        val rect = getTabRect(index)
        val isActive = index == activeIndex

        // 背景圆角矩形 (仅顶部圆角)
        val path = Path()
        path.addRoundRect(
            rect, floatArrayOf(
                tabCornerRadius, tabCornerRadius, // 左上
                tabCornerRadius, tabCornerRadius, // 右上
                0f, 0f,                           // 右下 (直角)
                0f, 0f                            // 左下 (直角)
            ), Path.Direction.CW
        )

        tabBgPaint.color = if (isActive) activeTabBg else inactiveTabBg
        canvas.drawPath(path, tabBgPaint)

        // 活动标签顶部彩色指示线
        if (isActive) {
            accentPaint.color = tab.accentColor
            canvas.drawRect(
                rect.left, rect.top,
                rect.right, rect.top + accentLineHeight,
                accentPaint
            )
        }

        // 图标 (首字母圆圈)
        val iconX = rect.left + textMarginH + iconSize / 2
        val iconY = tabHeight / 2f + 4f * density
        iconPaint.color = tab.accentColor
        canvas.drawCircle(iconX, tabHeight / 2f, iconSize / 2 + 2f * density,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tab.accentColor; alpha = 30
            })
        canvas.drawText(
            tab.title.first().uppercase(), iconX, iconY, iconPaint
        )

        // 标题文字
        val textX = rect.left + textMarginH + iconSize + 8f * density
        textPaint.color = if (isActive) textColorActive else textColor
        val textY = tabHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        val maxTextW = rect.width() - textMarginH * 2 - iconSize - closeBtnSize - 12f * density
        val titleText = if (textPaint.measureText(tab.title) > maxTextW) {
            // 截断 + ...
            var s = tab.title
            while (textPaint.measureText(s + "...") > maxTextW && s.isNotEmpty()) {
                s = s.dropLast(1)
            }
            s + "..."
        } else tab.title
        canvas.drawText(titleText, textX, textY, textPaint)

        // 关闭按钮 (×)
        val closeX = rect.right - closeBtnSize / 2 - 6f * density
        val closeY = tabHeight / 2f + 6f * density
        val closeRect = RectF(
            closeX - closeBtnSize / 2, tabHeight / 2f - closeBtnSize / 2,
            closeX + closeBtnSize / 2, tabHeight / 2f + closeBtnSize / 2
        )
        closeBtnRects.add(closeRect)

        // 高亮 (触碰反馈)
        if (closePressedIndex == index) {
            canvas.drawCircle(closeX, tabHeight / 2f, closeBtnSize / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = closeBtnHoverBg })
        }
        canvas.drawText("×", closeX, closeY, closeBtnPaint)
    }

    private fun drawAddButton(canvas: Canvas) {
        val cx = width - addBtnSize / 2 - 4f * density
        val cy = tabHeight / 2f
        // 背景 (悬停时)
        if (addPressed) {
            canvas.drawCircle(cx, cy, addBtnSize / 2,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0x20FFFFFF.toInt()
                })
        }
        canvas.drawText("+", cx, cy + 6f * density, addBtnPaint)
    }

    // ========== 触摸 ==========

    private var closePressedIndex: Int = -1
    private var addPressed: Boolean = false
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartOffset = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                dragStartX = event.x
                dragStartOffset = scrollOffset
                dragging = false
                closePressedIndex = -1
                addPressed = false

                // 检查 + 按钮
                if (event.x > width - addBtnSize - 8f * density) {
                    addPressed = true
                    invalidate()
                    return true
                }

                // 检查关闭按钮
                for (i in closeBtnRects.indices.reversed()) {
                    if (closeBtnRects[i].contains(event.x, event.y)) {
                        closePressedIndex = i
                        invalidate()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                if (event.x - dragStartX > 20f * density || event.x - dragStartX < -20f * density) {
                    dragging = true
                }
                if (dragging) {
                    scrollOffset = (dragStartOffset + event.x - dragStartX)
                        .coerceIn(-maxScroll, 0f)
                    invalidate()
                }
                lastTouchX = event.x
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (addPressed) {
                    addPressed = false
                    onAddTab?.invoke()
                    invalidate()
                    return true
                }
                if (closePressedIndex >= 0) {
                    val idx = closePressedIndex
                    closePressedIndex = -1
                    onTabClosed?.invoke(idx)
                    invalidate()
                    return true
                }
                if (!dragging) {
                    // 点击标签切换
                    for (i in tabs.indices) {
                        if (getTabRect(i).contains(event.x, event.y)) {
                            selectTab(i)
                            return true
                        }
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                closePressedIndex = -1
                addPressed = false
                invalidate()
            }
        }
        return super.onTouchEvent(event)
    }
}

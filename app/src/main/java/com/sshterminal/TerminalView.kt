package com.sshterminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.ClipboardManager
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.GestureDetectorCompat

/**
 * 自定义终端 View — 直接 Canvas 绘制屏幕缓冲区
 *
 * 替代 ScrollView+TextView 方案：
 * - Canvas 直接绘制，支持颜色/样式/光标闪烁
 * - 触摸滚动滚回区
 * - 直接键盘输入（Ctrl、方向键、Tab 等）
 * - 长按粘贴
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        color = -1
    }
    private val cursorPaint = Paint().apply {
        color = 0xFF00FF88.toInt()
    }
    private val bgPaint = Paint().apply {
        color = 0xFF0D0D1A.toInt()
    }
    private val selectionPaint = Paint().apply {
        color = 0x40FFFFFF.toInt()
    }

    private var fontWidth = 0f
    private var fontHeight = 0f

    var emulator: TerminalEmulator? = null
        set(value) {
            field = value
            value?.onBufferChanged = { postInvalidate() }
        }

    val buffer: TerminalBuffer?
        get() = emulator?.buffer

    // 触摸滚动
    private val gestureDetector = GestureDetectorCompat(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).text ?: return
            onKeyInput?.invoke(clip.toString())
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            buffer?.resetScrollback(); postInvalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            val buf = buffer ?: return false
            buf.setScrollbackOffset(buf.scrollbackOffset + (-vy / 500).toInt())
            postInvalidate(); return true
        }
    })
    private var lastTouchY = 0f
    private var scrollAcc = 0f
    private var isScrolling = false
    private var cursorVisible = true

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        textPaint.textSize = 42f
        updateFontMetrics()
        postDelayed({
            cursorVisible = !cursorVisible
            postInvalidate()
            postDelayed({ cursorVisible = !cursorVisible; postInvalidate() }, 500)
        }, 500)
    }

    private fun updateFontMetrics() {
        fontWidth = textPaint.measureText("W")
        val fm = textPaint.fontMetrics
        fontHeight = fm.descent - fm.ascent
        recalcSize()
    }

    private fun recalcSize() {
        if (width <= 0 || height <= 0) return
        val cols = (width / fontWidth).toInt().coerceAtLeast(20)
        val rows = (height / fontHeight).toInt().coerceAtLeast(5)
        buffer?.resize(rows, cols)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recalcSize()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val buf = buffer ?: return

        for (row in 0 until buf.rows) {
            val line = buf.getVisibleLine(row) ?: continue
            val y = row * fontHeight + fontHeight - textPaint.fontMetrics.descent

            for (col in 0 until buf.columns) {
                val cell = line[col]
                if (cell.ch == ' ' && cell.bgColor == TerminalBuffer.DEFAULT_BG) continue
                val x = col * fontWidth

                val fgIdx = if (cell.reverse) cell.bgColor else cell.fgColor
                val bgIdx = if (cell.reverse) cell.fgColor else cell.bgColor

                // 背景
                if (bgIdx != TerminalBuffer.DEFAULT_BG) {
                    val bgc = if (bgIdx in TerminalBuffer.ANSI_COLORS.indices)
                        TerminalBuffer.ANSI_COLORS[bgIdx] else TerminalBuffer.DEFAULT_BG_COLOR
                    bgPaint.color = bgc
                    canvas.drawRect(x, row * fontHeight, x + fontWidth, row * fontHeight + fontHeight, bgPaint)
                }

                // 前景
                val fgc = if (fgIdx in TerminalBuffer.ANSI_COLORS.indices)
                    TerminalBuffer.ANSI_COLORS[fgIdx] else TerminalBuffer.DEFAULT_FG_COLOR
                textPaint.color = fgc
                textPaint.isFakeBoldText = cell.bold
                textPaint.textSkewX = if (cell.italic) -0.25f else 0f
                canvas.drawText(cell.ch.toString(), x + 1, y, textPaint)

                // 下划线
                if (cell.underline) canvas.drawLine(x, row * fontHeight + fontHeight - 2, x + fontWidth, row * fontHeight + fontHeight - 2, textPaint)
            }
        }

        // 光标
        if (cursorVisible && hasFocus()) {
            val cx = buf.cursorCol * fontWidth
            val cy = buf.cursorRow * fontHeight
            cursorPaint.alpha = 180
            canvas.drawRect(cx, cy, cx + fontWidth, cy + fontHeight, cursorPaint)
            val cc = buf.getVisibleLine(buf.cursorRow)?.getOrNull(buf.cursorCol)
            if (cc != null) {
                textPaint.color = TerminalBuffer.DEFAULT_BG_COLOR
                canvas.drawText(cc.ch.toString(), cx + 1, cy + fontHeight - textPaint.fontMetrics.descent, textPaint)
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(e)
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { lastTouchY = e.y; scrollAcc = 0f; isScrolling = false; requestFocus(); return true }
            MotionEvent.ACTION_MOVE -> {
                scrollAcc += lastTouchY - e.y; lastTouchY = e.y
                if (kotlin.math.abs(scrollAcc) > fontHeight * 0.5f) {
                    val dir = if (scrollAcc > 0) 1 else -1
                    buffer?.let { it.setScrollbackOffset(it.scrollbackOffset + dir); postInvalidate() }
                    isScrolling = true; scrollAcc = 0f
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!isScrolling) {
                    requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                        ?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    var onKeyInput: ((String) -> Unit)? = null

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val buf = buffer ?: return super.onKeyDown(keyCode, event)
        buf.resetScrollback()
        val text = convertKey(keyCode, event) ?: return super.onKeyDown(keyCode, event)
        onKeyInput?.invoke(text)
        postInvalidate()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean = true

    private fun convertKey(keyCode: Int, event: KeyEvent?): String? {
        val ctrl = event?.hasModifiers(KeyEvent.META_CTRL_ON) == true
        if (ctrl) return when (keyCode) {
            KeyEvent.KEYCODE_A -> "\u0001"; KeyEvent.KEYCODE_B -> "\u0002"
            KeyEvent.KEYCODE_C -> "\u0003"; KeyEvent.KEYCODE_D -> "\u0004"
            KeyEvent.KEYCODE_E -> "\u0005"; KeyEvent.KEYCODE_F -> "\u0006"
            KeyEvent.KEYCODE_G -> "\u0007"; KeyEvent.KEYCODE_H -> "\u0008"
            KeyEvent.KEYCODE_I -> "\u0009"; KeyEvent.KEYCODE_J -> "\u000A"
            KeyEvent.KEYCODE_K -> "\u000B"; KeyEvent.KEYCODE_L -> "\u000C"
            KeyEvent.KEYCODE_M -> "\u000D"; KeyEvent.KEYCODE_N -> "\u000E"
            KeyEvent.KEYCODE_O -> "\u000F"; KeyEvent.KEYCODE_P -> "\u0010"
            KeyEvent.KEYCODE_Q -> "\u0011"; KeyEvent.KEYCODE_R -> "\u0012"
            KeyEvent.KEYCODE_S -> "\u0013"; KeyEvent.KEYCODE_T -> "\u0014"
            KeyEvent.KEYCODE_U -> "\u0015"; KeyEvent.KEYCODE_V -> "\u0016"
            KeyEvent.KEYCODE_W -> "\u0017"; KeyEvent.KEYCODE_X -> "\u0018"
            KeyEvent.KEYCODE_Y -> "\u0019"; KeyEvent.KEYCODE_Z -> "\u001A"
            KeyEvent.KEYCODE_SPACE -> "\u0000"
            else -> null
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007F"
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~"
            KeyEvent.KEYCODE_HOME -> "\u001B[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F"
            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_SPACE -> " "
            KeyEvent.KEYCODE_GRAVE -> "`"; KeyEvent.KEYCODE_MINUS -> "-"; KeyEvent.KEYCODE_EQUALS -> "="
            KeyEvent.KEYCODE_LEFT_BRACKET -> "["; KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
            KeyEvent.KEYCODE_BACKSLASH -> "\\"; KeyEvent.KEYCODE_SEMICOLON -> ";"
            KeyEvent.KEYCODE_APOSTROPHE -> "'"; KeyEvent.KEYCODE_COMMA -> ","
            KeyEvent.KEYCODE_PERIOD -> "."; KeyEvent.KEYCODE_SLASH -> "/"
            KeyEvent.KEYCODE_F1 -> "\u001BOP"; KeyEvent.KEYCODE_F2 -> "\u001BOQ"
            KeyEvent.KEYCODE_F3 -> "\u001BOR"; KeyEvent.KEYCODE_F4 -> "\u001BOS"
            KeyEvent.KEYCODE_F5 -> "\u001B[15~"; KeyEvent.KEYCODE_F6 -> "\u001B[17~"
            KeyEvent.KEYCODE_F7 -> "\u001B[18~"; KeyEvent.KEYCODE_F8 -> "\u001B[19~"
            KeyEvent.KEYCODE_F9 -> "\u001B[20~"; KeyEvent.KEYCODE_F10 -> "\u001B[21~"
            KeyEvent.KEYCODE_F11 -> "\u001B[23~"; KeyEvent.KEYCODE_F12 -> "\u001B[24~"
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                ((keyCode - KeyEvent.KEYCODE_0 + '0'.code).toChar()).toString()
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> {
                val base = if (event?.isShiftPressed == true) 'A'.code else 'a'.code
                (base + keyCode - KeyEvent.KEYCODE_A).toChar().toString()
            }
            else -> null
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                buffer?.resetScrollback()
                onKeyInput?.invoke(text.toString())
                postInvalidate()
                return true
            }
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN)
                    return this@TerminalView.onKeyDown(event.keyCode, event)
                return true
            }
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) { onKeyInput?.invoke("\u007F"); postInvalidate() }
                return true
            }
        }
    }
}

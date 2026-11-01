package com.asis.reev.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

enum class KbMode { LETTERS, SYMBOLS, MORE }
enum class ShiftState { OFF, ON, LOCKED }

class KeyboardView(ctx: Context, private val pal: Palette) : View(ctx) {

    interface Listener {
        fun onKeyText(text: String)
        fun onDelete()
        fun onDeleteWord()
        fun onEnter()
        fun onEmoji()
        fun onShift()
        fun onModeChange(mode: KbMode)
        fun onCursorMove(steps: Int)
        fun onKeyDownFeedback()
    }

    var listener: Listener? = null
    var overlay: OverlayView? = null
    var hapticsOn = true

    var mode = KbMode.LETTERS
        set(v) { field = v; rows = when (v) { KbMode.LETTERS -> Layouts.letters; KbMode.SYMBOLS -> Layouts.symbols; KbMode.MORE -> Layouts.moreSymbols }; layoutKeys(); invalidate() }
    var shift = ShiftState.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    /** IME action label: 0 = newline, else one of EditorInfo.IME_ACTION_*. */
    var enterAction = 0
        set(v) { field = v; invalidate() }

    private var rows: List<List<Key>> = Layouts.letters
    private val rowH = ctx.dp(54f)
    private val padV = ctx.dp(6f)
    private val gapH = ctx.dp(5f)
    private val gapV = ctx.dp(7f)
    private val radius = ctx.dp(8f)

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.shadow; alpha = 90 }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text; textAlign = Paint.Align.CENTER; textSize = ctx.dp(23f) }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text; textAlign = Paint.Align.CENTER; textSize = ctx.dp(15f); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.subText; textAlign = Paint.Align.RIGHT; textSize = ctx.dp(10.5f) }
    private val iconStroke = Icons.stroke(pal.text, ctx.dp(1.8f))
    private val accentIconStroke = Icons.stroke(pal.accentText, ctx.dp(2f))

    private val handler = Handler(Looper.getMainLooper())

    // --- touch state (single active key + roll-over) ---
    private class Touch(val id: Int, val key: Key, val x0: Float, val y0: Float) {
        var longPressed = false
        var spaceDrag = false
        var lastStepX = x0
        var deleteSwipe = false
    }
    private var active: Touch? = null
    private var lastShiftTap = 0L

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (rowH * 4 + padV * 2).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutKeys()

    private fun layoutKeys() {
        if (width == 0) return
        val unit = width / 10f
        rows.forEachIndexed { ri, row ->
            val total = row.sumOf { it.weight.toDouble() }.toFloat()
            val u = if (total > 10f) width / total else unit
            var x = (width - total * u) / 2f
            val top = padV + ri * rowH
            for (k in row) {
                k.l = x; k.r = x + k.weight * u; k.t = top; k.b = top + rowH
                x = k.r
            }
        }
    }

    private fun isSpecial(k: Key) = k.type != KeyType.CHAR && k.type != KeyType.SPACE

    override fun onDraw(c: Canvas) {
        c.drawColor(pal.bg)
        val pressed = active?.key
        for (row in rows) for (k in row) {
            if (k.type == KeyType.SPACER) continue
            val rect = RectF(k.l + gapH / 2, k.t + gapV / 2, k.r - gapH / 2, k.b - gapV / 2)
            val fill = when {
                k.type == KeyType.ENTER -> pal.accent
                k === pressed -> pal.keyPressed
                k.type == KeyType.SHIFT && shift != ShiftState.OFF -> pal.keyPressed
                isSpecial(k) -> pal.special
                else -> pal.key
            }
            c.drawRoundRect(RectF(rect.left, rect.top + 2, rect.right, rect.bottom + 2), radius, radius, shadowPaint)
            keyPaint.color = fill
            c.drawRoundRect(rect, radius, radius, keyPaint)
            drawLabel(c, k, rect)
        }
    }

    private fun drawLabel(c: Canvas, k: Key, rect: RectF) {
        val cx = rect.centerX(); val cy = rect.centerY()
        val s = rect.height()
        when (k.type) {
            KeyType.CHAR -> {
                val label = if (mode == KbMode.LETTERS && shift != ShiftState.OFF) k.label.uppercase() else k.label
                val fm = textPaint.fontMetrics
                c.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2, textPaint)
                k.hint?.let { if (mode == KbMode.LETTERS) c.drawText(it, rect.right - context.dp(5f), rect.top + context.dp(13f), hintPaint) }
            }
            KeyType.SHIFT -> Icons.shift(c, cx, cy, s * 0.42f, iconStroke, shift != ShiftState.OFF, shift == ShiftState.LOCKED)
            KeyType.DELETE -> Icons.backspace(c, cx, cy, s * 0.42f, iconStroke)
            KeyType.ENTER -> when (enterAction) {
                3 -> Icons.search(c, cx, cy, s * 0.5f, accentIconStroke)
                2, 4, 5, 6 -> {
                    val label = when (enterAction) { 2 -> "Go"; 4 -> "Send"; 5 -> "Next"; else -> "Done" }
                    val p = Paint(smallText).apply { color = pal.accentText }
                    val fm = p.fontMetrics
                    c.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2, p)
                }
                else -> Icons.enter(c, cx, cy, s * 0.5f, accentIconStroke)
            }
            KeyType.EMOJI -> Icons.smile(c, cx, cy, s * 0.62f, iconStroke)
            KeyType.SPACE -> {
                val p = Paint(hintPaint).apply { textAlign = Paint.Align.CENTER; textSize = context.dp(12f) }
                val fm = p.fontMetrics
                c.drawText("Reev", cx, cy - (fm.ascent + fm.descent) / 2, p)
            }
            KeyType.SYMBOLS, KeyType.LETTERS, KeyType.MORE_SYMBOLS -> {
                val fm = smallText.fontMetrics
                c.drawText(k.label, cx, cy - (fm.ascent + fm.descent) / 2, smallText)
            }
            KeyType.SPACER -> {}
        }
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (row in rows) for (k in row) if (k.type != KeyType.SPACER && k.contains(x, y)) return k
        // Clamp: nearest key in the row (forgiving edges / spacers)
        val ri = ((y - padV) / rowH).toInt().coerceIn(0, rows.size - 1)
        return rows[ri].filter { it.type != KeyType.SPACER }.minByOrNull { abs(it.cx - x) }
    }

    private fun overlayRect(k: Key): RectF {
        val ov = overlay ?: return RectF()
        val a = IntArray(2); val b = IntArray(2)
        getLocationInWindow(a); ov.getLocationInWindow(b)
        val dx = (a[0] - b[0]).toFloat(); val dy = (a[1] - b[1]).toFloat()
        return RectF(k.l + gapH / 2 + dx, k.t + gapV / 2 + dy, k.r - gapH / 2 + dx, k.b - gapV / 2 + dy)
    }

    private fun overlayX(x: Float): Float {
        val ov = overlay ?: return x
        val a = IntArray(2); val b = IntArray(2)
        getLocationInWindow(a); ov.getLocationInWindow(b)
        return x + a[0] - b[0]
    }

    private fun feedback() {
        if (hapticsOn) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        listener?.onKeyDownFeedback()
    }

    private val longPressRunnable = Runnable { onLongPress() }
    private var repeatDelay = 400L
    private var repeatCount = 0
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val t = active ?: return
            if (t.key.type != KeyType.DELETE || t.deleteSwipe) return
            repeatCount++
            if (repeatCount > 18) listener?.onDeleteWord() else listener?.onDelete()
            if (hapticsOn) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            handler.postDelayed(this, if (repeatCount > 18) 120 else 45)
        }
    }

    private fun onLongPress() {
        val t = active ?: return
        val k = t.key
        if (k.type == KeyType.CHAR && k.popup.isNotEmpty()) {
            t.longPressed = true
            val items = if (mode == KbMode.LETTERS && shift != ShiftState.OFF) k.popup.map { it.uppercase() } else k.popup
            overlay?.showPopup(overlayRect(k), items, (k.r - k.l).coerceAtLeast(context.dp(36f)))
            if (hapticsOn) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun press(id: Int, x: Float, y: Float) {
        val k = keyAt(x, y) ?: return
        active = Touch(id, k, x, y)
        feedback()
        invalidate()
        when (k.type) {
            KeyType.CHAR -> {
                val label = if (mode == KbMode.LETTERS && shift != ShiftState.OFF) k.label.uppercase() else k.label
                overlay?.showPreview(overlayRect(k), label)
                if (k.popup.isNotEmpty()) handler.postDelayed(longPressRunnable, 300)
            }
            KeyType.DELETE -> {
                listener?.onDelete()
                repeatCount = 0
                handler.postDelayed(repeatRunnable, repeatDelay)
            }
            else -> {}
        }
    }

    private fun release(commit: Boolean) {
        val t = active ?: return
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
        overlay?.hidePreview()
        val k = t.key
        if (commit) {
            if (t.longPressed) {
                overlay?.popupValue()?.let { listener?.onKeyText(it) }
            } else if (!t.spaceDrag && !t.deleteSwipe) when (k.type) {
                KeyType.CHAR -> listener?.onKeyText(if (mode == KbMode.LETTERS && shift != ShiftState.OFF) k.label.uppercase() else k.label)
                KeyType.SPACE -> listener?.onKeyText(" ")
                KeyType.ENTER -> listener?.onEnter()
                KeyType.EMOJI -> listener?.onEmoji()
                KeyType.SHIFT -> listener?.onShift()
                KeyType.SYMBOLS -> { mode = KbMode.SYMBOLS; listener?.onModeChange(mode) }
                KeyType.LETTERS -> { mode = KbMode.LETTERS; listener?.onModeChange(mode) }
                KeyType.MORE_SYMBOLS -> { mode = KbMode.MORE; listener?.onModeChange(mode) }
                else -> {}
            } else if (t.deleteSwipe) listener?.onDeleteWord()
        }
        overlay?.hidePopup()
        active = null
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> press(e.getPointerId(0), e.x, e.y)
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Fast typing: a second finger lands before the first lifts -> commit first.
                val i = e.actionIndex
                release(true)
                press(e.getPointerId(i), e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                val t = active ?: return true
                val i = e.findPointerIndex(t.id); if (i < 0) return true
                val x = e.getX(i); val y = e.getY(i)
                if (t.longPressed) { overlay?.movePopup(overlayX(x)); return true }
                when (t.key.type) {
                    KeyType.SPACE -> {
                        val step = context.dp(14f)
                        if (!t.spaceDrag && abs(x - t.x0) > context.dp(18f)) { t.spaceDrag = true; t.lastStepX = x }
                        if (t.spaceDrag) {
                            val n = ((x - t.lastStepX) / step).toInt()
                            if (n != 0) { listener?.onCursorMove(n); t.lastStepX += n * step; if (hapticsOn) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
                        }
                    }
                    KeyType.DELETE -> if (!t.deleteSwipe && t.x0 - x > context.dp(40f)) { t.deleteSwipe = true; handler.removeCallbacks(repeatRunnable) }
                    KeyType.CHAR -> {
                        // Slide to a neighbouring key before long-press fires = retarget.
                        val k = keyAt(x, y)
                        if (k != null && k !== t.key && k.type == KeyType.CHAR) {
                            handler.removeCallbacks(longPressRunnable)
                            active = Touch(t.id, k, x, y)
                            val label = if (mode == KbMode.LETTERS && shift != ShiftState.OFF) k.label.uppercase() else k.label
                            overlay?.showPreview(overlayRect(k), label)
                            if (k.popup.isNotEmpty()) handler.postDelayed(longPressRunnable, 300)
                            invalidate()
                        }
                    }
                    else -> {}
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val t = active
                if (t != null && e.getPointerId(e.actionIndex) == t.id) release(true)
            }
            MotionEvent.ACTION_UP -> release(true)
            MotionEvent.ACTION_CANCEL -> release(false)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
    }

    fun shiftTappedAt(now: Long): Boolean {
        val dbl = now - lastShiftTap < 320
        lastShiftTap = now
        return dbl
    }
}

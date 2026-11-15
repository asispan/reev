package com.asis.reev.ui

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Dictation panel: one big push-to-talk button (hold to talk, tap to lock). */
class VoicePanel(ctx: Context, private val pal: Palette) : View(ctx) {

    enum class State { IDLE, RECORDING, LATCHED, TRANSCRIBING, INSERTED, ERROR }

    interface Listener {
        fun onTalkStart()
        fun onTalkStop()
        fun onTalkCancel()
        fun onEngineTap()
        /** Long-press on the engine chip: cycle the dictation mode. */
        fun onModeTap()
        fun onDelete()
        fun onEnter()
        fun onSpace()
    }

    var listener: Listener? = null
    var hapticsOn = true
    /** Name of the mode the next dictation will use, shown in the idle prompt. */
    var modeLabel = ""
        set(v) { if (field != v) { field = v; invalidate(); a11y.invalidateRoot() } }

    var engineLabel = ""
        set(v) { field = v; invalidate(); a11y.invalidateRoot() }
    var state = State.IDLE
        set(v) { if (field != v) a11y.invalidateRoot(); field = v; updateAnim(); invalidate() }
    /** Where the last transcript went, for the INSERTED label ("Whatsapp"). */
    var insertedIn: String = ""
        set(v) { field = v; invalidate() }

    /** A problem to show instead of the usual prompt (engine not ready, mic error). Spoken to screen readers. */
    var hint: String? = null
        set(v) { if (v != null) announceForAccessibility(v); field = v; invalidate(); a11y.invalidateRoot() }

    /** 0..1 microphone level, smoothed. */
    private var level = 0f
    private var phase = 0f
    private val levels = FloatArray(48)
    private var levelIdx = 0

    fun pushLevel(v: Float) {
        level = level * 0.6f + v * 0.4f
        levels[levelIdx] = v; levelIdx = (levelIdx + 1) % levels.size
    }

    private val btnR = ctx.dp(44f)
    private val accent = Icons.fill(pal.accent)
    private val rec = Icons.fill(Color.parseColor("#FF4D5E"))
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val micFill = Icons.fill(Color.WHITE)
    private val micStroke = Icons.stroke(Color.WHITE, ctx.dp(3f))
    private val stroke = Icons.stroke(pal.text, ctx.dp(1.8f))
    private val spin = Icons.stroke(Color.WHITE, ctx.dp(3.5f))
    private val chip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.special }
    private val keyBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.special }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.keyPressed }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text; textSize = ctx.dp(13f); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val hintText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.subText; textSize = ctx.dp(12.5f); textAlign = Paint.Align.CENTER }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeWidth = ctx.dp(3f) }
    private val accentStroke = Icons.stroke(pal.accentText, ctx.dp(2f))

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { phase = it.animatedFraction; invalidate() }
    }

    private fun updateAnim() {
        if (state == State.IDLE) anim.cancel() else if (!anim.isStarted) anim.start()
    }

    private val rowH = ctx.dp(54f)
    private val padV = ctx.dp(6f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (rowH * 4 + padV * 2).toInt())
    }

    private val cx get() = width / 2f
    private val cy get() = height * 0.36f

    // bottom controls geometry
    private val ctlH get() = context.dp(44f)
    private val ctlY get() = height - context.dp(10f) - ctlH
    private fun chipRect(): RectF {
        val w = max(chipText.measureText(engineLabel) + context.dp(58f), context.dp(120f))
        val maxRight = spaceRect().left - context.dp(8f)
        return RectF(context.dp(10f), ctlY, minOf(context.dp(10f) + w, maxRight), ctlY + ctlH)
    }
    private fun enterRect() = RectF(width - context.dp(10f) - context.dp(64f), ctlY, width - context.dp(10f), ctlY + ctlH)
    private fun delRect(): RectF { val e = enterRect(); return RectF(e.left - context.dp(8f) - context.dp(64f), ctlY, e.left - context.dp(8f), ctlY + ctlH) }
    private fun spaceRect(): RectF { val d = delRect(); return RectF(d.left - context.dp(8f) - context.dp(64f), ctlY, d.left - context.dp(8f), ctlY + ctlH) }
    private fun cancelCenter() = Pair(cx - btnR - context.dp(56f), cy)

    override fun onDraw(c: Canvas) {
        c.drawColor(pal.bg)
        val active = state == State.RECORDING || state == State.LATCHED

        // waveform ring
        if (active) {
            val n = levels.size
            for (i in 0 until n) {
                val v = levels[(levelIdx + i) % n]
                val a = (i.toFloat() / n) * 2 * Math.PI + phase * 0.6
                val inner = btnR + context.dp(10f)
                val len = context.dp(4f) + v * context.dp(30f)
                bar.color = pal.accent; bar.alpha = (110 + v * 145).toInt().coerceAtMost(255)
                val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                c.drawLine(cx + ca * inner, cy + sa * inner, cx + ca * (inner + len), cy + sa * (inner + len), bar)
            }
            // glow
            ring.color = pal.accent; ring.alpha = 50
            c.drawCircle(cx, cy, btnR + context.dp(6f) + level * context.dp(14f), ring)
        } else if (state == State.IDLE) {
            ring.color = pal.accent; ring.alpha = 28
            c.drawCircle(cx, cy, btnR + context.dp(12f), ring)
        }

        // the button
        val scale = if (active) 1f + 0.04f * sin(phase * 2 * Math.PI).toFloat() else 1f
        c.drawCircle(cx, cy, btnR * scale, if (active) rec else accent)
        if (state == State.TRANSCRIBING) {
            val r = btnR * 0.55f
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), phase * 360f, 270f, false, spin)
        } else if (state == State.INSERTED) {
            Icons.check(c, cx, cy, btnR * 1.05f, micStroke)
        } else if (state == State.ERROR) {
            val r = btnR * 0.5f
            c.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), 0f, 359f, false, micStroke)
        } else if (state == State.LATCHED) {
            val s = btnR * 0.36f
            c.drawRoundRect(RectF(cx - s, cy - s, cx + s, cy + s), s * 0.25f, s * 0.25f, micFill)
        } else {
            Icons.mic(c, cx, cy, btnR * 1.05f, micFill, micStroke)
        }

        // cancel (only when recording)
        if (active) {
            val (x, y) = cancelCenter()
            c.drawCircle(x, y, context.dp(22f), keyBg)
            Icons.close(c, x, y, context.dp(30f), stroke)
        }

        // hint
        val h = hint ?: when (state) {
            State.IDLE -> (if (modeLabel.isNotEmpty()) "$modeLabel  ·  " else "") + "Hold to talk  ·  tap to lock"
            State.RECORDING -> "Listening… release to insert"
            State.LATCHED -> "Listening… tap to insert"
            State.TRANSCRIBING -> "Transcribing…"
            State.INSERTED -> if (insertedIn.isNotEmpty()) "Inserted in $insertedIn" else "Inserted"
            State.ERROR -> hint ?: "Didn't catch that"
        }
        c.drawText(h, cx, cy + btnR + context.dp(30f), hintText)

        // controls
        val cr = chipRect()
        c.drawRoundRect(cr, ctlH / 2, ctlH / 2, if (pressed == 1) pressPaint else chip)
        c.drawCircle(cr.left + context.dp(16f), cr.centerY(), context.dp(4f), accent)
        val fm = chipText.fontMetrics
        c.drawText(engineLabel, cr.left + context.dp(28f), cr.centerY() - (fm.ascent + fm.descent) / 2, chipText)
        Icons.chevronDown(c, cr.right - context.dp(14f), cr.centerY(), context.dp(18f), stroke)

        val sr = spaceRect(); c.drawRoundRect(sr, context.dp(10f), context.dp(10f), if (pressed == 4) pressPaint else keyBg)
        c.drawLine(sr.centerX() - context.dp(12f), sr.centerY() + context.dp(4f), sr.centerX() + context.dp(12f), sr.centerY() + context.dp(4f), stroke)
        c.drawLine(sr.centerX() - context.dp(12f), sr.centerY() + context.dp(4f), sr.centerX() - context.dp(12f), sr.centerY() - context.dp(1f), stroke)
        c.drawLine(sr.centerX() + context.dp(12f), sr.centerY() + context.dp(4f), sr.centerX() + context.dp(12f), sr.centerY() - context.dp(1f), stroke)
        val dr = delRect(); c.drawRoundRect(dr, context.dp(10f), context.dp(10f), if (pressed == 2) pressPaint else keyBg)
        Icons.backspace(c, dr.centerX(), dr.centerY(), context.dp(24f), stroke)
        val er = enterRect(); c.drawRoundRect(er, context.dp(10f), context.dp(10f), accent)
        Icons.enter(c, er.centerX(), er.centerY(), context.dp(26f), accentStroke)
    }

    // --- screen readers: one node per control, activated by double-tap ---
    // Hold-to-talk can't work under explore-by-touch, so the talk button toggles: once to start
    // (locked on), once more to stop and insert. Nothing is spoken on start or stop, because speech
    // from the screen reader would land in the recording; the haptic tick is the confirmation.
    private val a11y = VirtualNodes(this, ::a11yItems, ::a11yClick)

    private fun a11yItems(): List<A11yItem> {
        val out = ArrayList<A11yItem>(7)
        val active = state == State.RECORDING || state == State.LATCHED
        val r = btnR + context.dp(12f)
        out += A11yItem(5, when (state) {
            State.IDLE -> "Start dictation"
            State.RECORDING, State.LATCHED -> "Stop and insert"
            State.TRANSCRIBING -> "Transcribing"
            State.INSERTED -> if (insertedIn.isNotEmpty()) "Inserted in $insertedIn" else "Inserted"
            State.ERROR -> hint ?: "Didn't catch that"
        }, RectF(cx - r, cy - r, cx + r, cy + r), clickable = state != State.TRANSCRIBING)
        if (active) {
            val (x, y) = cancelCenter(); val cr = context.dp(26f)
            out += A11yItem(6, "Cancel dictation", RectF(x - cr, y - cr, x + cr, y + cr))
        }
        hint?.let { out += A11yItem(7, it, RectF(0f, cy + r, width.toFloat(), ctlY - context.dp(4f)), clickable = false) }
        out += A11yItem(1, "Speech engine, $engineLabel", chipRect())
        out += A11yItem(4, "Space", spaceRect())
        out += A11yItem(2, "Delete", delRect())
        out += A11yItem(3, "Enter", enterRect())
        return out
    }

    private fun a11yClick(item: A11yItem) {
        when (item.id) {
            5 -> {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                when (state) {
                    State.IDLE, State.INSERTED, State.ERROR -> { listener?.onTalkStart(); if (state == State.RECORDING) state = State.LATCHED }
                    State.RECORDING, State.LATCHED -> listener?.onTalkStop()
                    State.TRANSCRIBING -> {}
                }
            }
            1 -> { buzz(); listener?.onEngineTap() }
            2 -> { buzz(); listener?.onDelete() }
            3 -> { buzz(); listener?.onEnter() }
            4 -> { buzz(); listener?.onSpace() }
            6 -> { buzz(); listener?.onTalkCancel() }
        }
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = a11y.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    // --- touch ---
    private var pressed = 0 // 0 none, 1 chip, 2 del, 3 enter, 4 space, 5 button, 6 cancel
    private var downAt = 0L
    private var longPressFired = false
    private val longPress = Runnable { if (pressed == 1) { longPressFired = true; buzz(); listener?.onModeTap() } }
    private val handler = Handler(Looper.getMainLooper())
    private val delRepeat = object : Runnable {
        override fun run() { if (pressed == 2) { listener?.onDelete(); handler.postDelayed(this, 60) } }
    }

    private fun zone(x: Float, y: Float): Int {
        if (chipRect().contains(x, y)) return 1
        if (delRect().contains(x, y)) return 2
        if (enterRect().contains(x, y)) return 3
        if (spaceRect().contains(x, y)) return 4
        if ((state == State.RECORDING || state == State.LATCHED)) {
            val (ccx, ccy) = cancelCenter()
            if (hypot(x - ccx, y - ccy) < context.dp(30f)) return 6
        }
        if (hypot(x - cx, y - cy) < btnR + context.dp(28f)) return 5
        return 0
    }

    private fun buzz(c: Int = HapticFeedbackConstants.KEYBOARD_TAP) { if (hapticsOn) performHapticFeedback(c) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = zone(e.x, e.y)
                downAt = System.currentTimeMillis()
                when (pressed) {
                    5 -> when (state) {
                        State.IDLE, State.INSERTED, State.ERROR -> { buzz(HapticFeedbackConstants.VIRTUAL_KEY); listener?.onTalkStart() }
                        State.LATCHED -> {}
                        else -> {}
                    }
                    1 -> { handler.postDelayed(longPress, 450) }
                    2 -> { buzz(); listener?.onDelete(); handler.postDelayed(delRepeat, 400) }
                    0 -> {}
                    else -> buzz()
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val held = System.currentTimeMillis() - downAt
                val z = zone(e.x, e.y)
                when (pressed) {
                    5 -> when (state) {
                        State.RECORDING -> if (held < 350) state = State.LATCHED else { buzz(HapticFeedbackConstants.VIRTUAL_KEY); listener?.onTalkStop() }
                        State.LATCHED -> { buzz(HapticFeedbackConstants.VIRTUAL_KEY); listener?.onTalkStop() }
                        else -> {}
                    }
                    1 -> if (z == 1 && !longPressFired) listener?.onEngineTap()
                    3 -> if (z == 3) listener?.onEnter()
                    4 -> if (z == 4) listener?.onSpace()
                    6 -> if (z == 6) listener?.onTalkCancel()
                }
                handler.removeCallbacks(delRepeat); handler.removeCallbacks(longPress)
                longPressFired = false
                pressed = 0; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (pressed == 5 && state == State.RECORDING) listener?.onTalkStop()
                handler.removeCallbacks(delRepeat); handler.removeCallbacks(longPress); longPressFired = false; pressed = 0; invalidate()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        anim.cancel(); handler.removeCallbacksAndMessages(null)
    }
}

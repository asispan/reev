package com.asis.reev.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

/**
 * The strip above the keys.
 *  - Typing mode: [⚙] [suggestion | suggestion | suggestion] [🎤]
 *  - Voice mode : live transcript text, status line, [⌨] at right to go back.
 */
class TopBar(ctx: Context, private val pal: Palette) : View(ctx) {

    interface Listener {
        fun onSettings()
        fun onMic()
        fun onSuggestion(word: String)
        fun onBackToKeys()
    }

    var listener: Listener? = null
    var hapticsOn = true

    var voiceMode = false
        set(v) { field = v; invalidate() }
    var suggestions: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    /** Index of the suggestion that equals what was typed verbatim (rendered bold / center). */
    var transcript: String = ""
        set(v) { field = v; invalidate() }
    var status: String = ""
        set(v) { field = v; invalidate() }
    var transcriptPending = false
        set(v) { field = v; invalidate() }

    private val h = ctx.dp(48f)
    private val iconW = ctx.dp(48f)
    private val stroke = Icons.stroke(pal.text, ctx.dp(1.8f))
    private val accentStroke = Icons.stroke(pal.accentText, ctx.dp(1.8f))
    private val accentFill = Icons.fill(pal.accent)
    private val micFill = Icons.fill(pal.accentText)
    private val sugg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text; textSize = ctx.dp(16.5f); textAlign = Paint.Align.CENTER }
    private val divider = Paint().apply { color = pal.subText; alpha = 70; strokeWidth = ctx.dp(1f) }
    private val tPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text; textSize = ctx.dp(16f) }
    private val sPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.subText; textSize = ctx.dp(11.5f); typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); letterSpacing = 0.06f }
    private val pressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.keyPressed }
    private var pressedZone = -10

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(pal.bar)
        val cy = height / 2f
        if (voiceMode) { drawVoice(c); return }
        // settings icon
        if (pressedZone == -1) c.drawCircle(iconW / 2, cy, h * 0.4f, pressPaint)
        Icons.gear(c, iconW / 2, cy, h * 0.5f, stroke)
        // mic (accent pill)
        val mx = width - iconW / 2
        c.drawCircle(mx, cy, h * 0.36f, accentFill)
        Icons.mic(c, mx, cy, h * 0.52f, micFill, accentStroke)
        // suggestions
        val l = iconW; val r = width - iconW
        val n = 3
        val cell = (r - l) / n
        val order = arrangeSuggestions()
        for (i in 0 until n) {
            val s = order.getOrNull(i) ?: continue
            val x0 = l + i * cell
            if (pressedZone == i) c.drawRoundRect(RectF(x0 + 4, 6f, x0 + cell - 4, height - 6f), h * 0.2f, h * 0.2f, pressPaint)
            sugg.typeface = if (i == 1) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
            val text = TextUtils.ellipsize(s, TextPaint(sugg), cell - 16, TextUtils.TruncateAt.END).toString()
            val fm = sugg.fontMetrics
            c.drawText(text, x0 + cell / 2, cy - (fm.ascent + fm.descent) / 2, sugg)
            if (i > 0) c.drawLine(x0, h * 0.3f, x0, h * 0.7f, divider)
        }
    }

    /** Best suggestion in the middle like Gboard. */
    private fun arrangeSuggestions(): List<String?> = when (suggestions.size) {
        0 -> emptyList()
        1 -> listOf(null, suggestions[0], null)
        2 -> listOf(suggestions[1], suggestions[0], null)
        else -> listOf(suggestions[1], suggestions[0], suggestions[2])
    }

    private fun drawVoice(c: Canvas) {
        val pad = context.dp(14f)
        val right = width - iconW
        // back-to-keyboard
        if (pressedZone == 9) c.drawCircle(width - iconW / 2, height / 2f, h * 0.4f, pressPaint)
        Icons.keyboard(c, width - iconW / 2, height / 2f, h * 0.55f, stroke)
        val avail = (right - pad * 1.5f).toInt().coerceAtLeast(10)
        val text = transcript.ifEmpty { status }
        tPaint.color = if (transcript.isEmpty()) pal.subText else if (transcriptPending) pal.text else pal.text
        tPaint.alpha = if (transcriptPending && transcript.isNotEmpty()) 200 else 255
        // Show the tail of long transcripts (most recent words), max 2 lines.
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, tPaint, avail)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(50).build()
        val lines = sl.lineCount
        c.save()
        c.clipRect(0f, 0f, right, height.toFloat())
        val lineH = sl.height.toFloat() / lines
        val shown = minOf(lines, 2)
        val firstLine = lines - shown
        val y0 = (height - lineH * shown) / 2f - sl.getLineTop(firstLine)
        c.translate(pad, y0)
        sl.draw(c)
        c.restore()
    }

    private fun zoneAt(x: Float): Int {
        if (voiceMode) return if (x > width - iconW) 9 else -10
        if (x < iconW) return -1
        if (x > width - iconW) return 3
        val cell = (width - 2 * iconW) / 3
        return ((x - iconW) / cell).toInt().coerceIn(0, 2)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { pressedZone = zoneAt(e.x); invalidate() }
            MotionEvent.ACTION_UP -> {
                val z = zoneAt(e.x)
                if (z == pressedZone) {
                    if (hapticsOn) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    when (z) {
                        -1 -> listener?.onSettings()
                        3 -> listener?.onMic()
                        9 -> listener?.onBackToKeys()
                        in 0..2 -> arrangeSuggestions().getOrNull(z)?.let { listener?.onSuggestion(it) }
                    }
                }
                pressedZone = -10; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressedZone = -10; invalidate() }
        }
        return true
    }
}

package com.asis.reev.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Tiny hand-drawn vector icons so we don't depend on font glyph coverage. */
object Icons {
    private val path = Path()

    fun stroke(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = w
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    /** Shift arrow. filled=true for shift on, bar=true for caps lock. */
    fun shift(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint, filled: Boolean, bar: Boolean) {
        path.reset()
        val h = s * 0.5f
        path.moveTo(cx, cy - h)
        path.lineTo(cx + h, cy)
        path.lineTo(cx + h * 0.45f, cy)
        path.lineTo(cx + h * 0.45f, cy + h * 0.6f)
        path.lineTo(cx - h * 0.45f, cy + h * 0.6f)
        path.lineTo(cx - h * 0.45f, cy)
        path.lineTo(cx - h, cy)
        path.close()
        val old = p.style
        p.style = if (filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
        c.drawPath(path, p)
        p.style = old
        if (bar) c.drawLine(cx - h * 0.45f, cy + h * 0.95f, cx + h * 0.45f, cy + h * 0.95f, p)
    }

    fun backspace(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        path.reset()
        val w = s * 0.62f; val h = s * 0.4f
        path.moveTo(cx - w, cy)
        path.lineTo(cx - w * 0.45f, cy - h)
        path.lineTo(cx + w, cy - h)
        path.lineTo(cx + w, cy + h)
        path.lineTo(cx - w * 0.45f, cy + h)
        path.close()
        c.drawPath(path, p)
        val x = cx + w * 0.28f; val d = h * 0.45f
        c.drawLine(x - d, cy - d, x + d, cy + d, p)
        c.drawLine(x - d, cy + d, x + d, cy - d, p)
    }

    fun enter(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.5f; val h = s * 0.35f
        path.reset()
        path.moveTo(cx + w, cy - h)
        path.lineTo(cx + w, cy + h * 0.3f)
        path.lineTo(cx - w, cy + h * 0.3f)
        c.drawPath(path, p)
        path.reset()
        path.moveTo(cx - w + h * 0.6f, cy + h * 0.3f - h * 0.6f)
        path.lineTo(cx - w, cy + h * 0.3f)
        path.lineTo(cx - w + h * 0.6f, cy + h * 0.3f + h * 0.6f)
        c.drawPath(path, p)
    }

    fun search(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val r = s * 0.28f
        c.drawCircle(cx - r * 0.3f, cy - r * 0.3f, r, p)
        c.drawLine(cx + r * 0.45f, cy + r * 0.45f, cx + r * 1.3f, cy + r * 1.3f, p)
    }

    fun mic(c: Canvas, cx: Float, cy: Float, s: Float, fill: Paint, stroke: Paint) {
        val w = s * 0.2f; val h = s * 0.32f
        c.drawRoundRect(RectF(cx - w, cy - h - s * 0.08f, cx + w, cy + h * 0.55f), w, w, fill)
        val r = RectF(cx - w * 1.7f, cy - h * 0.9f, cx + w * 1.7f, cy + h * 0.95f)
        c.drawArc(r, 20f, 140f, false, stroke)
        c.drawLine(cx, cy + h * 0.95f, cx, cy + h * 1.35f, stroke)
        c.drawLine(cx - w * 0.9f, cy + h * 1.35f, cx + w * 0.9f, cy + h * 1.35f, stroke)
    }

    fun keyboard(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.55f; val h = s * 0.36f
        c.drawRoundRect(RectF(cx - w, cy - h, cx + w, cy + h), s * 0.08f, s * 0.08f, p)
        val dot = Paint(p).apply { style = Paint.Style.FILL }
        val r = s * 0.035f
        for (row in 0..1) for (i in 0..4) {
            c.drawCircle(cx - w * 0.68f + i * w * 0.34f, cy - h * 0.45f + row * h * 0.5f, r, dot)
        }
        c.drawLine(cx - w * 0.45f, cy + h * 0.5f, cx + w * 0.45f, cy + h * 0.5f, p)
    }

    fun smile(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val r = s * 0.38f
        c.drawCircle(cx, cy, r, p)
        val dot = Paint(p).apply { style = Paint.Style.FILL }
        c.drawCircle(cx - r * 0.38f, cy - r * 0.25f, r * 0.11f, dot)
        c.drawCircle(cx + r * 0.38f, cy - r * 0.25f, r * 0.11f, dot)
        c.drawArc(RectF(cx - r * 0.55f, cy - r * 0.4f, cx + r * 0.55f, cy + r * 0.55f), 25f, 130f, false, p)
    }

    fun gear(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val r = s * 0.22f
        c.drawCircle(cx, cy, r, p)
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val x1 = cx + (r * 1.25f) * Math.cos(a).toFloat(); val y1 = cy + (r * 1.25f) * Math.sin(a).toFloat()
            val x2 = cx + (r * 1.75f) * Math.cos(a).toFloat(); val y2 = cy + (r * 1.75f) * Math.sin(a).toFloat()
            c.drawLine(x1, y1, x2, y2, p)
        }
    }

    fun chevronDown(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val w = s * 0.3f
        path.reset(); path.moveTo(cx - w, cy - w * 0.4f); path.lineTo(cx, cy + w * 0.5f); path.lineTo(cx + w, cy - w * 0.4f)
        c.drawPath(path, p)
    }

    fun close(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val d = s * 0.25f
        c.drawLine(cx - d, cy - d, cx + d, cy + d, p); c.drawLine(cx - d, cy + d, cx + d, cy - d, p)
    }

    fun check(c: Canvas, cx: Float, cy: Float, s: Float, p: Paint) {
        val d = s * 0.3f
        path.reset(); path.moveTo(cx - d, cy); path.lineTo(cx - d * 0.25f, cy + d * 0.7f); path.lineTo(cx + d, cy - d * 0.6f)
        c.drawPath(path, p)
    }
}

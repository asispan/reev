package com.asis.reev.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Transparent layer above the whole IME that draws key previews and long-press popups,
 *  so the bubbles can extend over the top bar. */
class OverlayView(ctx: Context, private val pal: Palette) : View(ctx) {
    private var previewRect: RectF? = null
    private var previewLabel = ""
    private var popupRect: RectF? = null
    private var popupItems: List<String> = emptyList()
    private var popupCell = 0f
    var popupSel = 0
        private set

    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.popup; setShadowLayer(ctx.dp(6f), 0f, ctx.dp(2f), 0x55000000) }
    private val sel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accent }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = pal.text; textAlign = Paint.Align.CENTER; textSize = ctx.dp(28f); typeface = Typeface.DEFAULT
    }
    private val r = ctx.dp(10f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
    }

    fun showPreview(keyRect: RectF, label: String) {
        val w = keyRect.width() * 1.15f
        val h = keyRect.height() * 1.25f
        val cx = keyRect.centerX()
        var left = cx - w / 2
        left = left.coerceIn(0f, width - w)
        previewRect = RectF(left, keyRect.top - h + keyRect.height() * 0.1f, left + w, keyRect.top + keyRect.height() * 0.1f)
        previewLabel = label
        invalidate()
    }

    fun hidePreview() { if (previewRect != null) { previewRect = null; invalidate() } }

    fun showPopup(keyRect: RectF, items: List<String>, cell: Float) {
        previewRect = null
        popupItems = items
        popupCell = cell
        val w = cell * items.size
        val h = keyRect.height() * 1.05f
        var left = keyRect.centerX() - cell / 2
        if (left + w > width) left = width - w
        left = left.coerceAtLeast(0f)
        popupRect = RectF(left, keyRect.top - h - context.dp(4f), left + w, keyRect.top - context.dp(4f))
        popupSel = 0
        invalidate()
    }

    /** Update selection from an x coordinate in overlay space. */
    fun movePopup(x: Float) {
        val rect = popupRect ?: return
        val i = ((x - rect.left) / popupCell).toInt().coerceIn(0, popupItems.size - 1)
        if (i != popupSel) { popupSel = i; invalidate() }
    }

    fun popupShowing() = popupRect != null
    fun popupValue(): String? = popupItems.getOrNull(popupSel)
    fun hidePopup() { popupRect = null; popupItems = emptyList(); invalidate() }

    override fun onDraw(c: Canvas) {
        previewRect?.let { rc ->
            c.drawRoundRect(rc, r, r, bubble)
            val fm = txt.fontMetrics
            c.drawText(previewLabel, rc.centerX(), rc.centerY() - (fm.ascent + fm.descent) / 2, txt)
        }
        popupRect?.let { rc ->
            c.drawRoundRect(rc, r, r, bubble)
            val fm = txt.fontMetrics
            popupItems.forEachIndexed { i, s ->
                val cl = rc.left + i * popupCell
                val cell = RectF(cl + 3, rc.top + 3, cl + popupCell - 3, rc.bottom - 3)
                if (i == popupSel) c.drawRoundRect(cell, r * 0.8f, r * 0.8f, sel)
                txt.color = if (i == popupSel) pal.accentText else pal.text
                c.drawText(s, cell.centerX(), cell.centerY() - (fm.ascent + fm.descent) / 2, txt)
            }
            txt.color = pal.text
        }
    }
}

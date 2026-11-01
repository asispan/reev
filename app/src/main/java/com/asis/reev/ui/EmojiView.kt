package com.asis.reev.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class EmojiView(ctx: Context, private val pal: Palette, private val recents: () -> List<String>) : LinearLayout(ctx) {

    interface Listener {
        fun onEmoji(e: String)
        fun onDelete()
        fun onAbc()
        fun onSpace()
    }

    var listener: Listener? = null
    var hapticsOn = true

    private val cols = 8
    private val groups: List<Pair<String, List<String>>> = ctx.assets.open("emoji.tsv").bufferedReader().readLines()
        .filter { it.isNotBlank() }.map { line -> val (g, e) = line.split('\t', limit = 2); g to e.split(' ').filter { it.isNotEmpty() } }
    private val tabIcons = listOf("🕘", "😀", "👋", "🐻", "🍔", "✈️", "⚽", "💡", "❤️", "🏳️")

    // Flat list of rows: header strings or emoji
    private sealed class Item { data class Header(val title: String) : Item(); data class E(val e: String) : Item() }
    private var items = listOf<Item>()
    private val sectionStart = IntArray(groups.size + 1)

    private val grid = RecyclerView(ctx)
    private val tabs = TabStrip()
    private val bottom = BottomRow()

    private val rowH = ctx.dp(54f)
    private val padV = ctx.dp(6f)

    init {
        orientation = VERTICAL
        setBackgroundColor(pal.bg)
        rebuild()
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dpi(40)))
        val glm = GridLayoutManager(ctx, cols)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (items[position] is Item.Header) cols else 1
        }
        grid.layoutManager = glm
        grid.adapter = Adapter()
        grid.setHasFixedSize(true)
        grid.itemAnimator = null
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val first = glm.findFirstVisibleItemPosition()
                var sel = 0
                for (i in 0..groups.size) if (sectionStart[i] <= first) sel = i
                if (tabs.sel != sel) { tabs.sel = sel; tabs.invalidate() }
            }
        })
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, ctx.dpi(48)))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((rowH * 4 + padV * 2).toInt(), MeasureSpec.EXACTLY))
    }

    fun rebuild() {
        val list = mutableListOf<Item>()
        val rec = recents()
        sectionStart[0] = 0
        list += Item.Header("RECENT"); rec.forEach { list += Item.E(it) }
        groups.forEachIndexed { i, (g, e) ->
            sectionStart[i + 1] = list.size
            list += Item.Header(g.uppercase()); e.forEach { list += Item.E(it) }
        }
        items = list
        grid.adapter?.notifyDataSetChanged()
    }

    private fun buzz() { if (hapticsOn) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1
        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val tv = TextView(parent.context)
            if (viewType == 0) {
                tv.setTextColor(pal.subText); tv.textSize = 11f; tv.typeface = Typeface.DEFAULT_BOLD
                tv.letterSpacing = 0.08f
                tv.setPadding(context.dpi(12), context.dpi(10), 0, context.dpi(4))
                tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                tv.textSize = 27f; tv.gravity = Gravity.CENTER
                tv.setTextColor(pal.text)
                tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dpi(48))
                val bg = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(pal.keyPressed), null,
                    android.graphics.drawable.ColorDrawable(pal.key))
                tv.background = bg
                tv.setOnClickListener { v -> buzz(); (v.tag as? String)?.let { listener?.onEmoji(it) } }
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            when (val it = items[position]) {
                is Item.Header -> tv.text = it.title
                is Item.E -> { tv.text = it.e; tv.tag = it.e }
            }
        }
    }

    private inner class TabStrip : View(context) {
        var sel = 1
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = context.dp(19f); textAlign = Paint.Align.CENTER }
        private val ind = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.accent }
        override fun onDraw(c: Canvas) {
            c.drawColor(pal.bg)
            val n = tabIcons.size
            val w = width / n.toFloat()
            val fm = p.fontMetrics
            for (i in 0 until n) {
                p.alpha = if (i == sel) 255 else 140
                c.drawText(tabIcons[i], w * i + w / 2, height / 2f - (fm.ascent + fm.descent) / 2, p)
            }
            c.drawRoundRect(RectF(w * sel + w * 0.25f, height - context.dp(3f), w * sel + w * 0.75f, height.toFloat()), 4f, 4f, ind)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.action == MotionEvent.ACTION_UP) {
                val i = (e.x / (width / tabIcons.size.toFloat())).toInt().coerceIn(0, tabIcons.size - 1)
                buzz()
                sel = i; invalidate()
                (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(sectionStart[i], 0)
            }
            return true
        }
    }

    private inner class BottomRow : View(context) {
        private val key = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.text; textSize = context.dp(15f); textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val stroke = Icons.stroke(pal.text, context.dp(1.8f))
        private var pressed = -1
        private val h = Handler(Looper.getMainLooper())
        private val rep = object : Runnable { override fun run() { if (pressed == 2) { listener?.onDelete(); h.postDelayed(this, 50) } } }
        private fun rects(): List<RectF> {
            val g = context.dp(4f); val t = g; val b = height - g
            val u = width / 10f
            return listOf(RectF(g, t, u * 1.5f - g, b), RectF(u * 1.5f + g, t, u * 8.5f - g, b), RectF(u * 8.5f + g, t, width - g, b))
        }
        override fun onDraw(c: Canvas) {
            c.drawColor(pal.bg)
            val r = rects(); val rad = context.dp(8f)
            r.forEachIndexed { i, rc ->
                key.color = if (pressed == i) pal.keyPressed else if (i == 1) pal.key else pal.special
                c.drawRoundRect(rc, rad, rad, key)
            }
            val fm = txt.fontMetrics
            c.drawText("ABC", r[0].centerX(), r[0].centerY() - (fm.ascent + fm.descent) / 2, txt)
            Icons.backspace(c, r[2].centerX(), r[2].centerY(), r[2].height() * 0.42f, stroke)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val i = rects().indexOfFirst { it.contains(e.x, e.y) }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { pressed = i; buzz(); if (i == 2) { listener?.onDelete(); h.postDelayed(rep, 400) }; invalidate() }
                MotionEvent.ACTION_UP -> {
                    if (i == pressed) when (i) { 0 -> listener?.onAbc(); 1 -> listener?.onSpace() }
                    pressed = -1; h.removeCallbacks(rep); invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { pressed = -1; h.removeCallbacks(rep); invalidate() }
            }
            return true
        }
    }
}

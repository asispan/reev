package com.asis.reev

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.asis.reev.delivery.Delivery
import com.asis.reev.history.HistoryStore
import com.asis.reev.mode.DeliveryAction
import com.asis.reev.transform.Llm
import com.asis.reev.transform.Local
import com.asis.reev.ui.dpi

/**
 * Dictation history: what was said, what was delivered, and where.
 *
 * Retry re-runs the transform and copies the result — this screen has no InputConnection, so it can
 * never insert into the field behind it. Says so on the row.
 */
class HistoryActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var store: HistoryStore
    private lateinit var col: LinearLayout

    private val fg = Color.parseColor("#F3F3F6")
    private val sub = Color.parseColor("#9A9CA6")
    private val accent = Color.parseColor("#E2336B")
    private val card = Color.parseColor("#1B1C20")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transcripts are private: keep them out of screenshots, recordings and the recents preview.
        // Debug builds skip it so QA runs and bug reports can capture the screen; release builds always set it.
        if (!BuildConfig.DEBUG) window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        prefs = Prefs(this)
        store = HistoryStore(this)
        render()
    }

    private fun render() {
        val scroll = ScrollView(this)
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18), dpi(28), dpi(18), dpi(40))
        }
        scroll.addView(col)
        setContentView(scroll)

        col.addView(text("History", 30f, fg, bold = true))
        col.addView(text("Dictations from every app, newest first. Tap a row to see the original and the delivered text.", 13.5f, sub)
            .also { it.setPadding(0, dpi(4), 0, dpi(14)) })

        val entries = store.recent()
        if (entries.isEmpty()) {
            val msg = if (store.enabled) "Nothing yet. Dictate once and it lands here."
                else "History is off, so nothing is kept. Turn on \"Keep a dictation history\" in Reev settings."
            col.addView(text(msg, 14f, sub).also { it.setPadding(0, dpi(8), 0, 0) })
        }
        entries.forEach { e ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(card, 12)
                setPadding(dpi(14), dpi(12), dpi(14), dpi(12))
                isFocusable = true
                contentDescription = "${e.text}. ${e.mode}, ${e.appLabel}, ${e.whenLabel}"
                setOnClickListener { detail(e) }
            }
            row.addView(text(e.text.ifBlank { "(empty)" }, 15f, fg).also {
                it.maxLines = 2
                it.ellipsize = android.text.TextUtils.TruncateAt.END
            })
            val meta = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(6), 0, 0) }
            meta.addView(text("${e.mode} · ${e.appLabel} · ${e.whenLabel}" + if (e.status == HistoryStore.Entry.Status.COPIED) " · copied" else "", 12f, sub).also {
                it.maxLines = 1
                it.ellipsize = android.text.TextUtils.TruncateAt.END
                it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
            })
            meta.addView(text("›", 16f, sub).also { it.setPadding(dpi(8), 0, 0, 0) })
            row.addView(meta)
            col.addView(row, spaced())
        }

        if (entries.isNotEmpty()) {
            col.addView(button("Clear history") {
                AlertDialog.Builder(this).setMessage("Delete all ${entries.size} entries?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ -> store.clear(); render() }.show()
            }, spaced())
        }
    }

    private fun detail(e: HistoryStore.Entry) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(20), dpi(18), dpi(20), 0) }
        body.addView(text("Original transcription", 12.5f, accent, bold = true))
        body.addView(text(e.raw, 14.5f, fg).also { it.setPadding(0, dpi(4), 0, dpi(12)) })
        if (e.text != e.raw) {
            body.addView(text("Delivered", 12.5f, accent, bold = true))
            body.addView(text(e.text, 14.5f, fg).also { it.setPadding(0, dpi(4), 0, dpi(12)) })
        }
        body.addView(text("${e.mode} · ${e.appLabel} · ${e.whenLabel} · ${e.durationMs / 1000}s", 12f, sub))

        AlertDialog.Builder(this)
            .setTitle("Dictation")
            .setView(body)
            .setPositiveButton("Copy") { _, _ ->
                Delivery.perform(this, null, e.text, DeliveryAction.COPY, null, false)
            }
            .setNeutralButton("Retry") { _, _ ->
                // No input connection here: re-run the mode and copy the result.
                val modes = com.asis.reev.mode.ModeStore(this)
                val mode = modes.all().firstOrNull { it.name.equals(e.mode, true) } ?: modes.resolve(e.app)
                val tr = if (prefs.aiKey.isNotEmpty() && prefs.aiModel.isNotEmpty() && mode.prompt.isNotBlank()) Llm(prefs) else Local()
                val out = Thread {
                    val text = tr.transform(e.raw, mode.prompt, com.asis.reev.polish.CursorContext.EMPTY, false) { false }
                    runOnUiThread {
                        Delivery.perform(this, null, text, DeliveryAction.COPY, null, false)
                        AlertDialog.Builder(this).setMessage("Retried and copied to the clipboard.").setPositiveButton("OK", null).show()
                    }
                }
                out.start()
            }
            .setNegativeButton("Delete") { _, _ -> store.delete(e.id); render() }
            .show()
    }

    private fun spaced() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .also { it.bottomMargin = dpi(8) }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpi(radius).toFloat()
    }

    private fun button(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        background = rounded(accent, 12); setPadding(0, dpi(13), 0, dpi(13))
        isFocusable = true
        setOnClickListener { onTap() }
    }
}

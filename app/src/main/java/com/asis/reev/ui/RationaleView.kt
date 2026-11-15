package com.asis.reev.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Why Reev needs the microphone, shown inside the keyboard before the system dialog.
 *
 * Three rows carry the mechanism, the benefit and the limit — the same order that makes a permission
 * request a decision rather than a toll gate. The caller owns the actual request.
 */
class RationaleView(ctx: Context, private val pal: Palette) : LinearLayout(ctx) {

    interface Listener {
        fun onRationaleAgree()
        fun onRationaleDecline()
    }

    var listener: Listener? = null

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(14), dp(20), dp(16))
        setBackgroundColor(pal.bg)

        addView(TextView(ctx).apply {
            text = "How Reev hears you"
            textSize = 19f
            setTextColor(pal.text)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        })
        addView(TextView(ctx).apply {
            text = "The mic stays shut until you hold the button."
            textSize = 13f
            setTextColor(pal.subText)
            setPadding(0, dp(3), 0, dp(10))
        })
        row("Only while dictating", "Audio is recorded between the button and the tick, never in the background")
        row("Only where you send it", "Your chosen engine — the on-device model or your own API key")
        row("Nothing kept by default", "History is opt-in and stays in Reev's private storage")
        addView(TextView(ctx).apply {
            text = "Reev asks for the microphone next."
            textSize = 12.5f
            setTextColor(pal.subText)
            setPadding(0, dp(10), 0, dp(10))
        })

        val actions = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        actions.addView(pill("Decline", pal.key, pal.text) { listener?.onRationaleDecline() })
        actions.addView(View(ctx), LayoutParams(0, 1).apply { weight = 0.04f })
        actions.addView(pill("Agree", pal.accent, Color.WHITE) { listener?.onRationaleAgree() })
        addView(actions)
    }

    private fun row(title: String, body: String) {
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
        box.addView(TextView(context).apply {
            text = title; textSize = 14.5f; setTextColor(pal.text); typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(context).apply { text = body; textSize = 12.8f; setTextColor(pal.subText) })
        box.isFocusable = true
        box.contentDescription = "$title. $body"
        addView(box)
    }

    private fun pill(label: String, bg: Int, fg: Int, tap: () -> Unit) = TextView(context).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(11), 0, dp(11))
        background = GradientDrawable().apply { setColor(bg); cornerRadius = dp(22).toFloat() }
        isFocusable = true
        contentDescription = label
        setOnClickListener { tap() }
        layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT).apply { weight = 0.48f }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

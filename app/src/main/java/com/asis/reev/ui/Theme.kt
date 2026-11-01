package com.asis.reev.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue

/** Keyboard palette. Follows the system dark/light setting. */
data class Palette(
    val bg: Int,
    val key: Int,
    val keyPressed: Int,
    val special: Int,
    val text: Int,
    val subText: Int,
    val accent: Int,
    val accentText: Int,
    val bar: Int,
    val shadow: Int,
    val popup: Int,
) {
    companion object {
        fun of(ctx: Context): Palette {
            val night = when (com.asis.reev.Prefs(ctx).theme) {
                "dark" -> true
                "light" -> false
                else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    Configuration.UI_MODE_NIGHT_YES
            }
            return if (night) Palette(
                bg = Color.parseColor("#111215"),
                key = Color.parseColor("#2B2D33"),
                keyPressed = Color.parseColor("#44474F"),
                special = Color.parseColor("#1D1F23"),
                text = Color.parseColor("#F3F3F6"),
                subText = Color.parseColor("#8E9099"),
                accent = Color.parseColor("#7C5CFF"),
                accentText = Color.WHITE,
                bar = Color.parseColor("#111215"),
                shadow = Color.parseColor("#000000"),
                popup = Color.parseColor("#3A3D45"),
            ) else Palette(
                bg = Color.parseColor("#E9EBEF"),
                key = Color.parseColor("#FFFFFF"),
                keyPressed = Color.parseColor("#D3D6DD"),
                special = Color.parseColor("#CDD1D9"),
                text = Color.parseColor("#16171A"),
                subText = Color.parseColor("#6B6E78"),
                accent = Color.parseColor("#6A47FF"),
                accentText = Color.WHITE,
                bar = Color.parseColor("#E9EBEF"),
                shadow = Color.parseColor("#9AA0AA"),
                popup = Color.parseColor("#FFFFFF"),
            )
        }
    }
}

fun Context.dp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

fun Context.dpi(v: Int): Int = dp(v.toFloat()).toInt()

/*
 * Glide (gesture/swipe) typing decoder for Reev.
 *
 * Ported and adapted from FlorisBoard's StatisticalGlideTypingClassifier
 * (Copyright (C) The FlorisBoard Contributors, Apache License 2.0), which is itself based on
 * Étienne Desticourt's statistical classifier for AnySoftKeyboard
 * (https://github.com/AnySoftKeyboard/AnySoftKeyboard/pull/1870, Apache License 2.0),
 * following the SHARK2 shape+location approach.
 *
 * Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
 */
package com.asis.reev.glide

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Centre of a letter key in keyboard-view pixels. */
data class KeyCenter(val ch: Char, val x: Float, val y: Float, val w: Float, val h: Float)

class GlideDecoder(
    keys: List<KeyCenter>,
    /** Vocabulary: lowercase a–z words with raw corpus frequency. */
    words: List<Pair<String, Long>>,
) {
    companion object {
        private const val SAMPLING_POINTS = 200
        private const val SHAPE_STD = 22.08f
        private const val LOCATION_STD = 0.5109f
        private const val PRUNING_LENGTH_THRESHOLD = 8.42f
    }

    private val keyByChar = keys.associateBy { it.ch }
    private val keyList = keys
    private val radius = keys.minOf { min(it.w, it.h) }

    /** (first, last) letter → candidate words. */
    private val tree = HashMap<Long, ArrayList<String>>()
    private val freqScore = HashMap<String, Float>()
    private val idealLength = HashMap<String, Float>()

    init {
        val maxF = words.maxOfOrNull { it.second }?.coerceAtLeast(2) ?: 2
        val lnMax = ln(maxF.toDouble())
        for ((w, f) in words) {
            if (w.length < 2 || w.any { it !in 'a'..'z' }) continue
            tree.getOrPut(pairKey(w.first(), w.last())) { ArrayList() }.add(w)
            // log-scaled 0..255 frequency (same scale FlorisBoard's model uses)
            freqScore[w] = (255.0 * ln(f.coerceAtLeast(2).toDouble()) / lnMax).toFloat().coerceAtLeast(1f)
        }
    }

    private fun pairKey(a: Char, b: Char) = (a.code.toLong() shl 16) or b.code.toLong()

    /** Returns up to [max] words, best first. Points are in the same coordinate space as the keys. */
    fun decode(xs: FloatArray, ys: FloatArray, n: Int, max: Int = 5): List<String> {
        if (n < 2) return emptyList()
        val user = Gesture(); for (i in 0 until n) user.add(xs[i], ys[i])
        val startKeys = nearest(user.firstX(), user.firstY(), 2)
        val endKeys = nearest(user.lastX(), user.lastY(), 2)
        val candidates = ArrayList<String>()
        for (s in startKeys) for (e in endKeys) tree[pairKey(s, e)]?.let { candidates.addAll(it) }
        if (candidates.isEmpty()) return emptyList()

        val userLen = user.length()
        val userRes = user.resample(SAMPLING_POINTS)
        val userNorm = userRes.normalizeByBoxSide()

        val best = ArrayList<Pair<String, Double>>()
        for (word in candidates) {
            val ideals = idealGestures(word)
            val len = idealLength.getOrPut(word) { ideals[0].length() }
            if (abs(userLen - len) > PRUNING_LENGTH_THRESHOLD * radius) continue
            var wordBest = Double.NEGATIVE_INFINITY
            for (ideal in ideals) {
                val res = ideal.resample(SAMPLING_POINTS)
                val norm = res.normalizeByBoxSide()
                val shape = shapeDistance(norm, userNorm)
                val loc = locationDistance(res, userRes)
                val lp = logGauss(shape, SHAPE_STD) + logGauss(loc, LOCATION_STD * radius) +
                    ln((freqScore[word] ?: 1f).toDouble())
                if (lp > wordBest) wordBest = lp
            }
            if (wordBest > Double.NEGATIVE_INFINITY) best += word to wordBest
        }
        return best.sortedByDescending { it.second }.take(max).map { it.first }
    }

    private fun nearest(x: Float, y: Float, k: Int): List<Char> =
        keyList.sortedBy { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) }.take(k).map { it.ch }

    private fun idealGestures(word: String): List<Gesture> {
        val g = Gesture(); val loops = Gesture()
        var prev = '\u0000'; var hasLoops = false
        for (c in word) {
            val k = keyByChar[c] ?: continue
            if (c == prev) {
                // small loop on doubled letters (pool vs poll)
                loops.add(k.x + k.w / 4, k.y + k.h / 4); loops.add(k.x + k.w / 4, k.y - k.h / 4)
                loops.add(k.x - k.w / 4, k.y - k.h / 4); loops.add(k.x - k.w / 4, k.y + k.h / 4)
                hasLoops = true
                g.add(k.x, k.y)
            } else { g.add(k.x, k.y); loops.add(k.x, k.y) }
            prev = c
        }
        return if (hasLoops) listOf(g, loops) else listOf(g)
    }

    private fun logGauss(v: Float, std: Float): Double {
        val z = v / std
        return -ln(std * sqrt(2 * PI)) - 0.5 * z * z
    }

    private fun locationDistance(a: Gesture, b: Gesture): Float {
        var t = 0f
        for (i in 0 until SAMPLING_POINTS) t += abs(a.x(i) - b.x(i)) + abs(a.y(i) - b.y(i))
        return t / SAMPLING_POINTS / 2
    }

    private fun shapeDistance(a: Gesture, b: Gesture): Float {
        var t = 0f
        for (i in 0 until SAMPLING_POINTS) {
            val dx = a.x(i) - b.x(i); val dy = a.y(i) - b.y(i); t += sqrt(dx * dx + dy * dy)
        }
        return t
    }

    private class Gesture {
        private var xs = FloatArray(64); private var ys = FloatArray(64); var size = 0
        fun add(x: Float, y: Float) {
            if (size == xs.size) { xs = xs.copyOf(size * 2); ys = ys.copyOf(size * 2) }
            xs[size] = x; ys[size] = y; size++
        }
        fun x(i: Int) = if (size == 0) 0f else xs[min(i, size - 1)]
        fun y(i: Int) = if (size == 0) 0f else ys[min(i, size - 1)]
        fun firstX() = xs[0]; fun firstY() = ys[0]
        fun lastX() = xs[size - 1]; fun lastY() = ys[size - 1]
        fun length(): Float {
            var l = 0f
            for (i in 1 until size) { val dx = xs[i] - xs[i - 1]; val dy = ys[i] - ys[i - 1]; l += sqrt(dx * dx + dy * dy) }
            return l
        }

        /** Evenly re-sample along the path to exactly [n] points. */
        fun resample(n: Int): Gesture {
            val out = Gesture()
            val total = length()
            if (size == 1 || total <= 0f) { repeat(n) { out.add(xs[0], ys[0]) }; return out }
            val step = total / (n - 1)
            out.add(xs[0], ys[0])
            var acc = 0f
            var px = xs[0]; var py = ys[0]
            var i = 1
            while (i < size && out.size < n) {
                val dx = xs[i] - px; val dy = ys[i] - py
                val d = sqrt(dx * dx + dy * dy)
                if (acc + d >= step && d > 0f) {
                    val t = (step - acc) / d
                    val nx = px + t * dx; val ny = py + t * dy
                    out.add(nx, ny)
                    px = nx; py = ny; acc = 0f
                } else { acc += d; px = xs[i]; py = ys[i]; i++ }
            }
            while (out.size < n) out.add(xs[size - 1], ys[size - 1])
            return out
        }

        fun normalizeByBoxSide(): Gesture {
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in 0 until size) { minX = min(minX, xs[i]); maxX = max(maxX, xs[i]); minY = min(minY, ys[i]); maxY = max(maxY, ys[i]) }
            val side = max(max(maxX - minX, maxY - minY), 0.00001f)
            val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
            val out = Gesture()
            for (i in 0 until size) out.add((xs[i] - cx) / side, (ys[i] - cy) / side)
            return out
        }
    }
}

package com.asis.reev.glide

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** Offline check of the glide decoder against synthetic, noisy swipes on a phone-sized QWERTY. */
class GlideDecoderTest {
    private val w = 1272f; private val rowH = 189f; private val padV = 21f
    private fun keys(): List<KeyCenter> {
        val out = ArrayList<KeyCenter>()
        val u = w / 10f
        "qwertyuiop".forEachIndexed { i, c -> out += KeyCenter(c, u * i + u / 2, padV + rowH / 2, u, rowH) }
        "asdfghjkl".forEachIndexed { i, c -> out += KeyCenter(c, u * 0.5f + u * i + u / 2, padV + rowH * 1.5f, u, rowH) }
        "zxcvbnm".forEachIndexed { i, c -> out += KeyCenter(c, u * 1.5f + u * i + u / 2, padV + rowH * 2.5f, u, rowH) }
        return out
    }

    private fun vocab(): List<Pair<String, Long>> =
        File("src/main/assets/words.txt").readLines().mapNotNull { l ->
            val t = l.split('\t'); if (t.size == 2) t[0].lowercase() to t[1].toLong() else null
        }.filter { it.first.length >= 2 && it.first.all { c -> c in 'a'..'z' } }.take(30000)

    /** Human-ish swipe: pass near each key centre with jitter, sampled every ~8 px. */
    private fun swipe(word: String, k: Map<Char, KeyCenter>, rnd: Random, jitter: Float): Pair<FloatArray, FloatArray> {
        val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
        val pts = word.map { c -> val kc = k.getValue(c); (kc.x + (rnd.nextFloat() - .5f) * 2 * jitter * kc.w) to (kc.y + (rnd.nextFloat() - .5f) * 2 * jitter * kc.h) }
        for (i in 0 until pts.size - 1) {
            val (x0, y0) = pts[i]; val (x1, y1) = pts[i + 1]
            val d = kotlin.math.hypot(x1 - x0, y1 - y0); val steps = (d / 8f).toInt().coerceAtLeast(1)
            for (s in 0 until steps) { xs += x0 + (x1 - x0) * s / steps; ys += y0 + (y1 - y0) * s / steps }
        }
        xs += pts.last().first; ys += pts.last().second
        return xs.toFloatArray() to ys.toFloatArray()
    }

    @Test fun decodesCommonWords() {
        val kc = keys(); val byC = kc.associateBy { it.ch }
        val dec = GlideDecoder(kc, vocab())
        val words = listOf("hello", "world", "keyboard", "thanks", "tonight", "dinner", "meeting", "because",
            "people", "really", "morning", "together", "where", "would", "about", "think", "going", "great",
            "please", "tomorrow", "office", "message", "phone", "running", "late", "weekend", "coffee", "water",
            "family", "happy", "birthday", "again", "should", "little", "something", "everything", "doctor",
            "travel", "flight", "bangalore", "android", "voice", "typing", "simple", "quick", "brown", "jumps")
        val rnd = Random(7)
        var top1 = 0; var top3 = 0; var n = 0; var maxMs = 0L; var totalMs = 0L
        val misses = ArrayList<String>()
        for (word in words) repeat(3) {
            val (xs, ys) = swipe(word, byC, rnd, 0.28f)
            val t0 = System.nanoTime()
            val r = dec.decode(xs, ys, xs.size, 5)
            val ms = (System.nanoTime() - t0) / 1_000_000
            maxMs = maxOf(maxMs, ms); totalMs += ms; n++
            if (r.firstOrNull() == word) top1++ else misses += "$word->${r.take(3)}"
            if (word in r.take(3)) top3++
        }
        println("glide top1=${top1}/$n top3=${top3}/$n avg=${totalMs / n}ms max=${maxMs}ms")
        println("misses: " + misses.joinToString("; "))
        assertTrue("top-3 accuracy too low: $top3/$n", top3 >= n * 0.85)
    }

    @Test fun cleanSwipesTop1() {
        val kc = keys(); val byC = kc.associateBy { it.ch }
        val dec = GlideDecoder(kc, vocab())
        val rnd = Random(1)
        for (word in listOf("hello", "help", "pool", "poll", "the", "you", "and")) {
            val (xs, ys) = swipe(word, byC, rnd, 0f)
            println("clean $word -> ${dec.decode(xs, ys, xs.size, 3)}")
        }
    }
}

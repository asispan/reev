package com.asis.reev

import android.content.Context
import java.util.concurrent.Executors

/** Lightweight English word completion + 1-edit correction. Loaded off the main thread. */
class Suggest(ctx: Context) {
    @Volatile private var words: Array<String> = emptyArray()    // sorted lowercase
    @Volatile private var freq: Map<String, Long> = emptyMap()
    private val display = HashMap<String, String>()              // lowercase -> canonical ("i" -> "I")
    private val learned = HashMap<String, Long>()
    private val sp = ctx.getSharedPreferences("reev_words", Context.MODE_PRIVATE)

    private val contractions = mapOf(
        "im" to "I'm", "dont" to "don't", "cant" to "can't", "ive" to "I've", "youre" to "you're",
        "thats" to "that's", "didnt" to "didn't", "doesnt" to "doesn't", "isnt" to "isn't", "wont" to "won't",
        "id" to "I'd", "ill" to "I'll", "hes" to "he's", "shes" to "she's", "theyre" to "they're",
        "wasnt" to "wasn't", "couldnt" to "couldn't", "wouldnt" to "wouldn't", "shouldnt" to "shouldn't",
        "lets" to "let's", "whats" to "what's", "theres" to "there's", "aint" to "ain't", "havent" to "haven't",
        "arent" to "aren't", "werent" to "weren't", "youll" to "you'll", "youve" to "you've", "theyll" to "they'll",
        "weve" to "we've", "hows" to "how's", "wheres" to "where's", "whos" to "who's",
    )

    init {
        Executors.newSingleThreadExecutor().execute {
            val f = HashMap<String, Long>(48000)
            ctx.assets.open("words.txt").bufferedReader().forEachLine { line ->
                val tab = line.indexOf('\t'); if (tab <= 0) return@forEachLine
                val w = line.substring(0, tab); val c = line.substring(tab + 1).toLongOrNull() ?: 1
                val lw = w.lowercase()
                if (w != lw) display[lw] = w
                f[lw] = c
            }
            sp.all.forEach { (k, v) -> (v as? Int)?.let { learned[k] = it.toLong() } }
            freq = f
            words = f.keys.toTypedArray().also { it.sort() }
        }
    }

    private fun score(w: String): Long = (freq[w] ?: 0L) + (learned[w] ?: 0L) * 2_000_000L

    private fun canon(w: String, typed: String): String {
        val base = display[w] ?: w
        return when {
            typed.length > 1 && typed.all { it.isUpperCase() } -> base.uppercase()
            typed.firstOrNull()?.isUpperCase() == true -> base.replaceFirstChar { it.uppercase() }
            else -> base
        }
    }

    fun isKnown(w: String) = freq.containsKey(w.lowercase()) || learned.containsKey(w.lowercase())

    /** Returns up to 3 suggestions; element 0 is the best (auto-pick candidate). Typed word is included verbatim. */
    fun suggest(typed: String): List<String> {
        if (typed.isEmpty() || words.isEmpty()) return emptyList()
        val low = typed.lowercase()
        val out = LinkedHashSet<String>()
        contractions[low]?.let { out += canon(it.lowercase(), typed).let { s -> if (it.startsWith("I")) it else s } }

        // prefix completions
        val arr = words
        var lo = 0; var hi = arr.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (arr[m] < low) lo = m + 1 else hi = m }
        val cands = ArrayList<String>()
        var i = lo
        while (i < arr.size && arr[i].startsWith(low) && cands.size < 4000) { cands += arr[i]; i++ }
        learned.keys.filter { it.startsWith(low) }.forEach { cands += it }
        val top = cands.distinct().sortedByDescending { score(it) + if (it == low) Long.MAX_VALUE / 4 else 0 }.take(3)

        val exact = isKnown(low)
        if (!exact && low.length >= 2) {
            // 1-edit corrections, ranked by frequency
            edits1W(low).filter { freq.containsKey(it.key) }.entries.sortedByDescending { score(it.key) * it.value }.take(2).map { it.key }.forEach { out += canon(it, typed) }
        }
        top.forEach { out += canon(it, typed) }
        val list = out.toMutableList()
        // keep what they typed visible (unless it's already there)
        if (list.none { it.equals(typed, true) }) list.add(minOf(1, list.size), typed)
        else { // put exact typed match first
            val idx = list.indexOfFirst { it.equals(typed, true) }
            if (idx > 0 && exact && contractions[low] == null) { val e = list.removeAt(idx); list.add(0, e) }
        }
        return list.take(3)
    }

    /** Best non-verbatim correction for autocorrect on space, or null. Conservative. */
    fun autocorrect(typed: String): String? {
        if (typed.length < 2 || words.isEmpty()) return null
        val low = typed.lowercase()
        contractions[low]?.let { return if (it.startsWith("I")) it else canon(it, typed) }
        if (low == "i") return "I"
        if (typed.any { it.isDigit() }) return null
        if (low in slang) return null
        val own = score(low)
        if (isKnown(low)) {
            // Rare dictionary word right next to a very common one ("helo" -> "hello").
            if (own > 2000 || learned.containsKey(low)) return null
            val best = bestEdit(low) ?: return null
            return if (score(best) > own * 300 && score(best) > 100_000) canon(best, typed) else null
        }
        val best = bestEdit(low) ?: return null
        return if ((freq[best] ?: 0) > 2000) canon(best, typed) else null
    }

    fun learn(word: String) {
        val w = word.lowercase().trim { !it.isLetter() && it != '\'' }
        if (w.length < 2 || w.any { !it.isLetter() && it != '\'' }) return
        val n = (learned[w] ?: 0L) + 1
        learned[w] = n
        if (!freq.containsKey(w) && n >= 1) sp.edit().putInt(w, n.toInt()).apply()
    }

    private val rowsKb = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private fun adjacent(a: Char, b: Char): Boolean {
        val ra = rowsKb.indexOfFirst { a in it }; val rb = rowsKb.indexOfFirst { b in it }
        if (ra < 0 || rb < 0 || kotlin.math.abs(ra - rb) > 1) return false
        val xa = rowsKb[ra].indexOf(a) + ra * 0.5; val xb = rowsKb[rb].indexOf(b) + rb * 0.5
        return kotlin.math.abs(xa - xb) <= 1.0
    }

    /** 1-edit neighbours with a plausibility weight (doubled letters, transpositions, adjacent keys rank higher). */
    private fun edits1W(w: String): Map<String, Double> {
        val letters = "abcdefghijklmnopqrstuvwxyz'"
        val res = HashMap<String, Double>()
        fun put(s: String, v: Double) { if (s != w && (res[s] ?: 0.0) < v) res[s] = v }
        for (i in 0..w.length) {
            if (i < w.length) put(w.removeRange(i, i + 1), 0.6)
            if (i < w.length - 1) put(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2), 2.0)
            for (c in letters) {
                if (i < w.length) put(w.substring(0, i) + c + w.substring(i + 1), if (adjacent(c, w[i])) 1.0 else 0.15)
                val dup = (i > 0 && w[i - 1] == c) || (i < w.length && w[i] == c)
                put(w.substring(0, i) + c + w.substring(i), if (dup) 3.0 else 0.6)
            }
        }
        return res
    }

    /** Only plausible typos (no far-away substitutions) are eligible for auto-replacement. */
    private fun bestEdit(low: String): String? =
        edits1W(low).filter { it.value >= 0.6 && freq.containsKey(it.key) }.maxByOrNull { score(it.key) * it.value }?.key

    private val slang = setOf("thx", "pls", "plz", "lol", "omg", "btw", "idk", "imo", "ok", "okay", "u", "ur", "gonna",
        "wanna", "gotta", "ya", "yeah", "nah", "brb", "ttyl", "np", "ty", "tbh", "asap", "fyi", "lmk", "hmm", "haha", "hahaha")

    private fun edits1(w: String): Set<String> = edits1W(w).keys
}

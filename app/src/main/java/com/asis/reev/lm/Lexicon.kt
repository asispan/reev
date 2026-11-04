package com.asis.reev.lm

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.ln

/**
 * Reev's language model: word frequencies (English + Hinglish), word-pair counts for context,
 * and an apostrophe/contraction table. Pure Kotlin (no Android) so it's unit-testable on the JVM.
 * Built by tools/build_lexicon.py.
 */
class Lexicon(
    wordsTsv: InputStream,
    bigramsBin: InputStream?,
    contractionsTsv: InputStream?,
) {
    data class Contraction(val form: String, val count: Long, val plainCount: Long, val ambiguous: Boolean)

    private val wordById: Array<String>
    private val ids = HashMap<String, Int>(90000)
    private val uni: LongArray
    private val tags: ByteArray            // bit0 = English, bit1 = Hinglish
    private val totalUni: Double
    private val sorted: Array<String>
    private val cum: LongArray
    private val sortedIds: IntArray

    private var biOff = IntArray(0)
    private var biNext = IntArray(0)
    private var biCnt = ShortArray(0)
    private val biTotal: LongArray

    private val contractions = HashMap<String, MutableList<Contraction>>()
    private val contractionForms = HashSet<String>()

    /** Words the user has typed/accepted, with counts. Owned by the Android wrapper. */
    val learned = HashMap<String, Long>()

    /** When false, Hinglish-only words are ignored for suggestions, autocorrect and prediction. */
    @Volatile var hinglish = true

    init {
        val ws = ArrayList<String>(70000); val fs = ArrayList<Long>(70000); val ts = ArrayList<Byte>(70000)
        wordsTsv.bufferedReader().forEachLine { line ->
            val p = line.split('\t'); if (p.size < 2) return@forEachLine
            ws += p[0]; fs += p[1].toLongOrNull() ?: 1L
            val t = p.getOrElse(2) { "e" }
            ts += ((if ('e' in t) 1 else 0) or (if ('h' in t) 2 else 0)).toByte()
        }
        wordById = ws.toTypedArray()
        uni = LongArray(ws.size) { fs[it] }
        tags = ByteArray(ws.size) { ts[it] }
        for (i in wordById.indices) ids[wordById[i]] = i
        totalUni = uni.sum().toDouble()
        val order = wordById.indices.sortedBy { wordById[it] }
        sortedIds = order.toIntArray()
        sorted = Array(order.size) { wordById[order[it]] }
        cum = LongArray(sorted.size + 1).also { c -> for (i in sorted.indices) c[i + 1] = c[i] + uni[sortedIds[i]] }

        if (bigramsBin != null) {
            val d = DataInputStream(bigramsBin.buffered(1 shl 16))
            val magic = ByteArray(4); d.readFully(magic)
            require(String(magic) == "RVB1") { "bad bigram file" }
            val v = d.readInt(); val n = d.readInt()
            biOff = IntArray(v + 1) { d.readInt() }
            biNext = IntArray(n); biCnt = ShortArray(n)
            for (i in 0 until n) { biNext[i] = d.readInt(); biCnt[i] = d.readShort() }
        }
        biTotal = LongArray(wordById.size) { x ->
            if (x + 1 >= biOff.size) 0L else { var s = 0L; for (j in biOff[x] until biOff[x + 1]) s += cnt(j); s }
        }

        contractionsTsv?.bufferedReader()?.forEachLine { line ->
            val p = line.split('\t'); if (p.size < 5) return@forEachLine
            val c = Contraction(p[1], p[2].toLong(), p[3].toLong(), p[4] == "1")
            contractions.getOrPut(p[0]) { ArrayList() }.add(c)
            contractionForms += p[1]
        }
    }

    private fun cnt(j: Int) = biCnt[j].toInt() and 0xffff

    // ---------------------------------------------------------------- basics
    fun norm(w: String) = w.lowercase().replace('\u2019', '\'').replace('\u2018', '\'')

    private fun allowed(id: Int) = hinglish || (tags[id].toInt() and 1) != 0

    fun isKnown(w: String): Boolean {
        val n = norm(w)
        val id = ids[n]
        return (id != null && allowed(id)) || learned.containsKey(n)
    }

    /** In the built-in dictionary (not just learned). */
    fun inDictionary(w: String) = ids.containsKey(norm(w))

    fun unigram(w: String): Long = (ids[w]?.let { if (allowed(it)) uni[it] else 0L } ?: 0L) + (learned[w] ?: 0L) * 2_000_000L

    fun bigram(prev: String?, w: String): Int {
        if (prev == null) return 0
        val x = ids[prev] ?: return 0; val y = ids[w] ?: return 0
        if (x + 1 >= biOff.size) return 0
        for (j in biOff[x] until biOff[x + 1]) if (biNext[j] == y) return cnt(j)
        return 0
    }

    /** Interpolated log-probability of [w] after [prev] (word-pair evidence backed off to frequency). */
    fun logP(prev: String?, w: String): Double {
        val pu = (unigram(w) + 1.0) / totalUni
        val x = prev?.let { ids[it] }
        if (x == null || biTotal[x] == 0L) return ln(pu)
        val pb = bigram(prev, w) / biTotal[x].toDouble()
        return ln(0.65 * pb + 0.35 * pu)
    }

    // ---------------------------------------------------------------- display
    fun display(word: String, capFirst: Boolean, allCaps: Boolean): String {
        var b = word
        if (b == "i" || b.startsWith("i'")) b = "I" + b.substring(1)
        return when { allCaps -> b.uppercase(); capFirst -> b.replaceFirstChar { it.uppercase() }; else -> b }
    }

    private fun canon(w: String, typed: String) = display(w,
        capFirst = typed.firstOrNull()?.isUpperCase() == true,
        allCaps = typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() })

    fun contractionsOf(plain: String): List<Contraction> = contractions[norm(plain)] ?: emptyList()

    // ---------------------------------------------------------------- suggestions
    /** Up to 3 suggestions, best first, ranked with the previous word as context. */
    fun suggest(typed: String, prev: String?): List<String> {
        if (typed.isEmpty()) return emptyList()
        val low = norm(typed)
        val p = prev?.let { norm(it) }
        val cands = LinkedHashSet<String>()
        // prefix completions (all forms, incl. "we'll" for "we")
        val (a, b) = range(low)
        var i = a
        val pre = ArrayList<String>()
        while (i < b && pre.size < 4000) { val id = sortedIds[i]; if (allowed(id)) pre += sorted[i]; i++ }
        learned.keys.filter { it.startsWith(low) }.forEach { pre += it }
        pre.sortedByDescending { logP(p, it) }.take(4).forEach { cands += it }
        // apostrophe forms of what was typed: well -> we'll, its -> it's
        val cons = contractions[low.replace("'", "")].orEmpty()
        cons.forEach { cands += it.form }
        // corrections for unknown words
        val known = isKnown(low)
        if (!known && low.length >= 2) {
            edits1W(low).filter { isKnown(it.key) }.entries
                .sortedByDescending { logP(p, it.key) + ln(it.value) }.take(2).forEach { cands += it.key }
        }
        val ranked = cands.toMutableList()
        // rank: context probability, with the exact typed word getting a strong bonus
        ranked.sortByDescending { w ->
            logP(p, w) + when {
                w == low && known -> 2.5
                w.replace("'", "") == low.replace("'", "") -> 1.5   // same letters, apostrophe differs
                w.startsWith(low) -> 0.0
                else -> -0.5
            }
        }
        val out = ranked.take(3).toMutableList()
        // always surface the apostrophe form when the typed word has one
        cons.firstOrNull()?.let { c -> if (c.form !in out && low != c.form) { if (out.size >= 3) out[2] = c.form else out += c.form } }
        // keep what they typed visible
        if (out.none { it == low }) { if (out.size >= 3) out[out.size - 1] = low else out += low }
        val shown = out.distinct().map { if (it == low) typed else canon(it, typed) }
        return shown.take(3)
    }

    /** Next-word predictions after [prev] (when the cursor sits after a space). */
    fun nextWords(prev: String, k: Int = 3): List<String> {
        val x = ids[norm(prev)] ?: return emptyList()
        if (x + 1 >= biOff.size) return emptyList()
        val out = ArrayList<String>(k)
        for (j in biOff[x] until biOff[x + 1]) {
            val id = biNext[j]
            if (!allowed(id)) continue
            val w = wordById[id]
            if (w.length == 1 && w != "a" && w != "i") continue
            out += display(w, false, false)
            if (out.size == k) break
        }
        return out
    }

    // ---------------------------------------------------------------- autocorrect
    private val slang = setOf("thx", "pls", "plz", "lol", "omg", "btw", "idk", "imo", "ok", "okay", "u", "ur", "gonna",
        "wanna", "gotta", "ya", "yeah", "nah", "brb", "ttyl", "np", "ty", "tbh", "asap", "fyi", "lmk", "hmm", "haha", "hahaha")

    /** Replacement to apply when space is pressed after [typed], or null. Conservative. */
    fun autocorrect(typed: String, prev: String?): String? {
        if (typed.length < 2) return if (typed == "i") "I" else null
        val low = norm(typed)
        if (typed.any { it.isDigit() } || low in slang) return null
        // unambiguous missing apostrophe: dont -> don't, im -> I'm (but not well/its/were)
        contractions[low]?.firstOrNull()?.let { c -> if (!c.ambiguous) return canon(c.form, typed) }
        val p = prev?.let { norm(it) }
        val own = unigram(low)
        if (isKnown(low)) {
            if (own > 2000 || learned.containsKey(low)) return null
            val best = bestEdit(low, p) ?: return null
            return if (unigram(best) > own * 300 && unigram(best) > 100_000) canon(best, typed) else null
        }
        val best = bestEdit(low, p) ?: return null
        return if (unigram(best) > 2000) canon(best, typed) else null
    }

    /**
     * After the next word is typed, decide whether the previous word should get its apostrophe:
     * "well see" -> "we'll", but "well done" stays. Only fires on overwhelming pair evidence.
     */
    fun retroCorrect(w1: String, w2: String): String? {
        val a = norm(w1); val b = norm(w2)
        val cons = contractions[a] ?: return null
        val plain = bigram(a, b)
        var best: Contraction? = null; var bestC = 0
        for (c in cons) { val n = bigram(c.form, b); if (n > bestC) { bestC = n; best = c } }
        val c = best ?: return null
        return if (bestC >= 4 && bestC > 6 * (plain + 1)) canon(c.form, w1) else null
    }

    // ---------------------------------------------------------------- tap & glide support
    private fun range(prefix: String): Pair<Int, Int> {
        var lo = 0; var hi = sorted.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (sorted[m] < prefix) lo = m + 1 else hi = m }
        val start = lo
        val upper = prefix + '\uffff'
        hi = sorted.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (sorted[m] < upper) lo = m + 1 else hi = m }
        return start to lo
    }

    /** P(next letter a..z | word prefix), smoothed. */
    fun letterPrior(prefix: String): FloatArray? {
        val p = norm(prefix)
        if (p.any { it !in 'a'..'z' && it != '\'' }) return null
        val masses = LongArray(26) { val (a, b) = range(p + ('a' + it)); cum[b] - cum[a] }
        val total = masses.sum().toDouble()
        if (total <= 0) return null
        val alpha = total * 0.01 + 1.0
        return FloatArray(26) { ((masses[it] + alpha) / (total + 26 * alpha)).toFloat() }
    }

    /** Most frequent plain a–z words (incl. Hinglish if enabled) plus learned words. */
    fun glideVocab(limit: Int = 30000): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>(limit + learned.size)
        for (id in wordById.indices) {          // words.tsv is frequency-sorted
            if (out.size >= limit) break
            val w = wordById[id]
            if (w.length >= 2 && allowed(id) && w.all { it in 'a'..'z' }) out += w to uni[id]
        }
        val have = out.mapTo(HashSet()) { it.first }
        learned.forEach { (w, n) -> if (w !in have && w.length >= 2 && w.all { it in 'a'..'z' }) out += w to n * 5_000L }
        return out
    }

    // ---------------------------------------------------------------- edits
    private val rowsKb = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private fun adjacent(a: Char, b: Char): Boolean {
        val ra = rowsKb.indexOfFirst { a in it }; val rb = rowsKb.indexOfFirst { b in it }
        if (ra < 0 || rb < 0 || abs(ra - rb) > 1) return false
        val xa = rowsKb[ra].indexOf(a) + ra * 0.5; val xb = rowsKb[rb].indexOf(b) + rb * 0.5
        return abs(xa - xb) <= 1.0
    }

    /** 1-edit neighbours weighted by plausibility (doubled letters, transpositions, adjacent keys higher). */
    fun edits1W(w: String): Map<String, Double> {
        val letters = "abcdefghijklmnopqrstuvwxyz'"
        val res = HashMap<String, Double>()
        fun put(s: String, v: Double) { if (s != w && (res[s] ?: 0.0) < v) res[s] = v }
        for (i in 0..w.length) {
            if (i < w.length) put(w.removeRange(i, i + 1), 0.6)
            if (i < w.length - 1) put(w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2), 2.0)
            for (c in letters) {
                if (i < w.length) put(w.substring(0, i) + c + w.substring(i + 1), if (adjacent(c, w[i])) 1.0 else 0.15)
                val dup = (i > 0 && w[i - 1] == c) || (i < w.length && w[i] == c)
                put(w.substring(0, i) + c + w.substring(i), if (dup) 5.0 else 0.6)
            }
        }
        return res
    }

    private fun bestEdit(low: String, prev: String?): String? =
        edits1W(low).filter { it.value >= 0.6 && isKnown(it.key) }
            .maxByOrNull { logP(prev, it.key) + ln(it.value) }?.key
}

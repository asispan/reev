package com.asis.reev

import android.content.Context
import com.asis.reev.lm.Lexicon
import java.util.concurrent.Executors

/**
 * Android wrapper around [Lexicon]: loads the language assets off the main thread and persists the
 * words the user teaches it. All language logic lives in Lexicon (unit-tested on the JVM).
 */
class Suggest(ctx: Context) {
    @Volatile private var lex: Lexicon? = null
    private val sp = ctx.getSharedPreferences("reev_words", Context.MODE_PRIVATE)
    private val prefs = Prefs(ctx)
    // Settings → "Forget learned words" clears the store; drop the in-memory counts with it.
    private val clearListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
        if (key == null || p.all.isEmpty()) lex?.learned?.clear()
    }

    init {
        sp.registerOnSharedPreferenceChangeListener(clearListener)
        Executors.newSingleThreadExecutor().execute {
            val a = ctx.assets
            val l = Lexicon(a.open("lexicon/words.tsv"), a.open("lexicon/bigrams.bin"), a.open("lexicon/contractions.tsv"))
            sp.all.forEach { (k, v) -> (v as? Int)?.let { l.learned[k] = it.toLong() } }
            l.hinglish = prefs.hinglish
            lex = l
            ready = true
        }
    }

    @Volatile var ready = false
        private set

    fun setHinglish(on: Boolean) { lex?.hinglish = on }

    fun suggest(typed: String, prev: String?): List<String> = lex?.suggest(typed, prev) ?: emptyList()
    fun nextWords(prev: String): List<String> = lex?.nextWords(prev) ?: emptyList()
    fun autocorrect(typed: String, prev: String?): String? = lex?.autocorrect(typed, prev)
    fun retroCorrect(w1: String, w2: String): String? = lex?.retroCorrect(w1, w2)
    fun contractionsOf(plain: String): List<String> = lex?.contractionsOf(plain)?.map { it.form } ?: emptyList()
    fun isKnown(w: String) = lex?.isKnown(w) ?: false
    fun letterPrior(prefix: String): FloatArray? = lex?.letterPrior(prefix)
    fun glideVocab(limit: Int = 30000): List<Pair<String, Long>> = lex?.glideVocab(limit) ?: emptyList()
    fun display(word: String, capFirst: Boolean, allCaps: Boolean): String =
        lex?.display(word, capFirst, allCaps) ?: word

    fun learn(word: String) {
        val l = lex ?: return
        val w = l.norm(word).trim { !it.isLetter() && it != '\'' }
        // Only plain words: no digits, symbols, emails or URLs, nothing long enough to be a token or ID.
        if (w.length < 2 || w.length > MAX_WORD || w.any { !it.isLetter() && it != '\'' }) return
        val n = (l.learned[w] ?: 0L) + 1
        l.learned[w] = n
        if (l.inDictionary(w)) return   // dictionary words are counted in memory only
        val ed = sp.edit().putInt(w, n.toInt())
        // Bounded store: evict the least-used words beyond MAX_STORED.
        val all = sp.all
        if (all.size >= MAX_STORED) all.entries.sortedBy { (it.value as? Int) ?: 0 }.take(all.size - MAX_STORED + 1).forEach { ed.remove(it.key) }
        ed.apply()
    }

    companion object {
        const val MAX_WORD = 24
        const val MAX_STORED = 2000
        /** Deletes every word Reev has learned on this phone. */
        fun forgetAll(ctx: Context) = ctx.getSharedPreferences("reev_words", Context.MODE_PRIVATE).edit().clear().apply()
    }
}

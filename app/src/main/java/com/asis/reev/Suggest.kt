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

    init {
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
        if (w.length < 2 || w.any { !it.isLetter() && it != '\'' }) return
        val n = (l.learned[w] ?: 0L) + 1
        l.learned[w] = n
        if (!l.inDictionary(w)) sp.edit().putInt(w, n.toInt()).apply()
    }
}

package com.asis.reev.lm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** With Hinglish off, romanised-Hindi words never come back: not as predictions, completions or via learned counts. */
class HinglishToggleTest {
    private val lex: Lexicon by lazy {
        val d = listOf("src/main/assets/lexicon", "app/src/main/assets/lexicon").first { File(it, "words.tsv").exists() }
        Lexicon(File("$d/words.tsv").inputStream(), File("$d/bigrams.bin").inputStream(), File("$d/contractions.tsv").inputStream())
    }
    private val hinglish = setOf("hai", "toh", "kya", "yaar", "nahi", "alag", "mein", "ki", "ka")

    @Test fun offHidesDualTaggedWords() {
        lex.hinglish = false
        val next = lex.nextWords("hi")
        assertTrue("predictions after 'hi': $next", next.none { it in hinglish })
        assertTrue(lex.suggest("ha", null).none { it == "hai" })
        lex.learned["yaar"] = 5
        assertFalse(lex.suggest("yaa", null).drop(1).contains("yaar"))
        lex.learned.remove("yaar")
    }

    @Test fun onKeepsThem() {
        lex.hinglish = true
        assertTrue(lex.suggest("ha", null).contains("hai") || lex.nextWords("hi").any { it in hinglish })
    }

    @Test fun englishWordsStay() {
        lex.hinglish = false
        assertTrue(lex.suggest("mai", null).contains("main"))
    }
}

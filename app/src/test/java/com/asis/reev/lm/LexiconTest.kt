package com.asis.reev.lm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class LexiconTest {
    companion object {
        lateinit var lex: Lexicon
        @BeforeClass @JvmStatic fun load() {
            val d = "src/main/assets/lexicon"
            lex = Lexicon(File("$d/words.tsv").inputStream(), File("$d/bigrams.bin").inputStream(), File("$d/contractions.tsv").inputStream())
        }
    }

    @Test fun stripOffersApostropheForms() {
        assertTrue(lex.suggest("well", "think").toString(), "we'll" in lex.suggest("well", "think"))
        assertTrue("it's" in lex.suggest("its", null))
        assertTrue("we're" in lex.suggest("were", null))
        assertTrue("I'll" in lex.suggest("ill", null))
        println("well|think -> ${lex.suggest("well", "think")}   well|very -> ${lex.suggest("well", "very")}")
        println("its|and -> ${lex.suggest("its", "and")}   were|you -> ${lex.suggest("were", "you")}")
    }

    @Test fun learningDoesNotDrownContext() {
        repeat(5) { lex.learned["well"] = (lex.learned["well"] ?: 0L) + 1 }
        val s = lex.suggest("well", "think")
        lex.learned.remove("well")
        assertEquals(s.toString(), "we'll", s.first())
    }

    @Test fun contextFixes() {
        assertEquals("we'll", lex.retroCorrect("well", "see"))
        assertNull(lex.retroCorrect("well", "done"))
        assertEquals("it's", lex.retroCorrect("its", "raining"))
        assertNull(lex.retroCorrect("its", "own"))
        assertEquals("We'll", lex.retroCorrect("Well", "need"))
    }

    @Test fun autocorrectBasics() {
        assertEquals("don't", lex.autocorrect("dont", null))
        assertEquals("I'm", lex.autocorrect("im", null))
        assertEquals("won't", lex.autocorrect("wont", null))
        assertNull(lex.autocorrect("well", null))      // ambiguous: never on first space
        assertNull(lex.autocorrect("its", null))
        assertEquals("hello", lex.autocorrect("helo", null))
        assertEquals("world", lex.autocorrect("wrld", "hello"))
        assertNull(lex.autocorrect("thx", null))
        assertNull(lex.autocorrect("hai", null))       // Hinglish words are words
        assertNull(lex.autocorrect("nahi", null))
        assertNull(lex.autocorrect("yaar", null))
    }

    @Test fun hinglishPredictionsAndToggle() {
        println("kya -> ${lex.nextWords("kya")}  bhai -> ${lex.nextWords("bhai")}  see you -> ${lex.nextWords("you")}")
        assertTrue(lex.suggest("nah", "kya").toString(), lex.suggest("nah", "kya").any { it.startsWith("nahi") })
        lex.hinglish = false
        val off = lex.suggest("nah", null)
        lex.hinglish = true
        println("hinglish off: nah -> $off")
        assertTrue(off.none { it == "nahi" })
    }

    /** Held-out Tatoeba sentences (never seen by the build): strip apostrophes and see what Reev restores. */
    @Test fun heldOutApostropheRestoration() {
        val f = File("../.lexicon-cache/heldout_en.txt")
        // Corpus evaluation: needs the held-out set from tools/build_lexicon.py. Reported as skipped, not passed, without it.
        Assume.assumeTrue("held-out file missing; run tools/build_lexicon.py", f.exists())
        val tok = Regex("[a-z]+(?:'[a-z]+)?")
        var shouldFix = 0; var fixed = 0; var wrongFix = 0; var plainSeen = 0; var nextHits = 0; var nextTotal = 0
        f.readLines().take(40000).forEach { line ->
            line.lowercase().replace('\u2019', '\'').split(Regex("[.!?,;:\"()]+")).forEach { clause ->
                val ws = tok.findAll(clause).map { it.value }.toList()
                for (i in ws.indices) {
                    val w = ws[i]; val next = ws.getOrNull(i + 1); val prev = ws.getOrNull(i - 1)
                    if (prev != null && nextTotal < 20000 && i % 3 == 0) {
                        nextTotal++; if (lex.nextWords(prev).map { it.lowercase() }.contains(w)) nextHits++
                    }
                    val plain = w.replace("'", "")
                    val cons = lex.contractionsOf(plain)
                    if (cons.isEmpty()) continue
                    if (w.contains('\'')) {
                        shouldFix++
                        val auto = lex.autocorrect(plain, prev)
                        val got = auto ?: next?.let { lex.retroCorrect(plain, it) }
                        if (got != null && got.lowercase() == w) fixed++
                    } else {
                        plainSeen++
                        val auto = lex.autocorrect(plain, prev)
                        val got = auto ?: next?.let { lex.retroCorrect(plain, it) }
                        if (got != null) wrongFix++
                    }
                }
            }
        }
        println("held-out: apostrophe restored ${fixed}/${shouldFix} (${100 * fixed / maxOf(1, shouldFix)}%), " +
            "correct plain words wrongly changed ${wrongFix}/${plainSeen} (${"%.2f".format(100.0 * wrongFix / maxOf(1, plainSeen))}%), " +
            "next-word top-3 ${nextHits}/${nextTotal} (${100 * nextHits / maxOf(1, nextTotal)}%)")
        assertTrue(wrongFix <= plainSeen * 0.02)
    }
}

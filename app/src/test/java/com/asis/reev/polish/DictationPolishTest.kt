package com.asis.reev.polish

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationPolishTest {

    private val dict = setOf("hey", "running", "late", "the", "meeting")
    private fun known(w: String) = w in dict

    @Test fun emptyFieldCapitalisesAndAddsTrailingSpace() {
        val ctx = CursorContext.EMPTY
        val plan = DictationPolish.plan(ctx, ::known, "hey")
        assertFalse(plan.leadingSpace)
        assertTrue(plan.capitalise)
        assertEquals("Hey there. ", DictationPolish.apply("hey there.", plan, ctx, ::known))
    }

    @Test fun midSentenceGetsSpaceAndStaysLowercase() {
        val ctx = CursorContext("we were late, so", "")
        val plan = DictationPolish.plan(ctx, ::known, "running")
        assertTrue(plan.leadingSpace)
        assertFalse(plan.capitalise)
        assertEquals(" running late", DictationPolish.apply("Running late", plan, ctx, ::known))
    }

    @Test fun midWordStillGetsASpace() {
        val ctx = CursorContext("ok,were", "")
        val plan = DictationPolish.plan(ctx, ::known, "late")
        assertTrue(plan.leadingSpace)
        assertEquals(" late", DictationPolish.apply("late", plan, ctx, ::known))
    }

    @Test fun afterPunctuationCapitalisesWithoutAnExtraSpace() {
        val ctx = CursorContext("we are late. ", "")
        val plan = DictationPolish.plan(ctx, ::known, "the")
        assertFalse(plan.leadingSpace)
        assertTrue(plan.capitalise)
        assertEquals("The meeting moved", DictationPolish.apply("the meeting moved", plan, ctx, ::known))
    }

    @Test fun afterOpenBracketNoSpace() {
        val ctx = CursorContext("see (", "")
        val plan = DictationPolish.plan(ctx, ::known, "hey")
        assertFalse(plan.leadingSpace)
        assertEquals("Hey there", DictationPolish.apply("Hey there", plan, ctx, ::known))
    }

    @Test fun trailingSpaceSkippedWhenCaretSitsAgainstPunctuation() {
        val ctx = CursorContext("", ", anyway")
        val plan = DictationPolish.plan(ctx, ::known, "hey")
        assertEquals("Hey there.", DictationPolish.apply("hey there.", plan, ctx, ::known))
    }

    @Test fun unknownCapitalisedWordKeepsItsCaseMidSentence() {
        val ctx = CursorContext("we met", "")
        val plan = DictationPolish.plan(ctx, ::known, "Asis")
        assertEquals(" Asis today", DictationPolish.apply("Asis today", plan, ctx, ::known))
    }

    @Test fun loneIKeepsItsCapital() {
        val ctx = CursorContext("and", "")
        val plan = DictationPolish.plan(ctx, { true }, "I")
        assertEquals(" I think so", DictationPolish.apply("I think so", plan, ctx, { true }))
    }

    @Test fun contextSlicesAtTheSelectionAndClamps() {
        val ctx = CursorContext.of("hello world", 5, 6)
        assertEquals("hello", ctx.before)
        assertEquals("world", ctx.after)
        assertEquals(CursorContext.EMPTY, CursorContext.of("", 3, 9))
        assertEquals("hello", CursorContext.of("hello", 99, 99).before)
    }

    @Test fun blankBeforeDetectsLeadingWhitespaceOnly() {
        assertTrue(CursorContext("   \n", "").blankBefore)
        assertFalse(CursorContext(" a", "").blankBefore)
    }
}

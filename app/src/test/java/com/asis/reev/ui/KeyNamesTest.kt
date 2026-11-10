package com.asis.reev.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every key must have a spoken name a screen-reader user can tell apart from its neighbours. */
class KeyNamesTest {
    private fun names(mode: KbMode, rows: List<List<Key>>, shift: ShiftState = ShiftState.OFF) =
        rows.flatten().filter { it.type != KeyType.SPACER }.map { KeyNames.name(it, mode, shift, 0) }

    @Test fun everyKeyHasAName() {
        for ((mode, rows) in listOf(KbMode.LETTERS to Layouts.letters(true), KbMode.LETTERS to Layouts.letters(false),
            KbMode.SYMBOLS to Layouts.symbols(true), KbMode.MORE to Layouts.moreSymbols(true))) {
            for (shift in ShiftState.entries) {
                val n = names(mode, rows, shift)
                assertTrue("blank name in $mode/$shift: $n", n.none { it.isBlank() })
                assertEquals("duplicate names in $mode/$shift: $n", n.size, n.toSet().size)
            }
        }
    }

    @Test fun symbolsAreSpelledOut() {
        // A bare punctuation mark is often skipped or mis-read by speech engines.
        for (rows in listOf(Layouts.letters(true), Layouts.symbols(true), Layouts.moreSymbols(true))) {
            for (k in rows.flatten().filter { it.type == KeyType.CHAR }) {
                val ch = k.label[0]
                if (!ch.isLetterOrDigit()) assertTrue("no spoken name for '${k.label}'", KeyNames.charName(k.label).length > 1)
            }
        }
    }

    @Test fun lettersFollowShift() {
        val q = Layouts.letters(false)[0][0]
        assertEquals("q", KeyNames.name(q, KbMode.LETTERS, ShiftState.OFF, 0))
        assertEquals("Capital Q", KeyNames.name(q, KbMode.LETTERS, ShiftState.ON, 0))
        assertEquals("Capital Q", KeyNames.name(q, KbMode.LETTERS, ShiftState.LOCKED, 0))
        // Shift doesn't change what a symbol key types.
        assertEquals("1", KeyNames.typed("1", KbMode.SYMBOLS, ShiftState.ON))
    }

    @Test fun shiftAndEnterDescribeTheirState() {
        val shift = Key(KeyType.SHIFT); val enter = Key(KeyType.ENTER)
        assertEquals("Shift", KeyNames.name(shift, KbMode.LETTERS, ShiftState.OFF, 0))
        assertEquals("Shift on", KeyNames.name(shift, KbMode.LETTERS, ShiftState.ON, 0))
        assertEquals("Caps lock on", KeyNames.name(shift, KbMode.LETTERS, ShiftState.LOCKED, 0))
        assertEquals(listOf("Enter", "Go", "Search", "Send", "Next", "Done"),
            listOf(0, 2, 3, 4, 5, 6).map { KeyNames.name(enter, KbMode.LETTERS, ShiftState.OFF, it) })
    }

    @Test fun longPressAlternativesFitTheActionSlots() {
        val most = listOf(Layouts.letters(true), Layouts.symbols(true), Layouts.moreSymbols(true))
            .flatten().flatten().maxOf { it.popup.size }
        assertTrue("a key has $most alternatives but only ${KeyNames.MAX_ALTS} action slots", most <= KeyNames.MAX_ALTS)
    }
}

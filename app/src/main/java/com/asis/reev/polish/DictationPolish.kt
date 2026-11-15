package com.asis.reev.polish

/**
 * Mechanical polish applied to a finished transcript before it is delivered.
 *
 * Every decision reads the [CursorContext] rather than the transcript, so the same sentence lands
 * correctly at a field start, mid-word, after a comma or inside a bracket. Pure and unit-tested;
 * the IME only supplies the context and commits the result.
 */
object DictationPolish {

    /** Characters that hug the following word instead of sitting next to it. */
    private const val OPENERS = "([{\"'“‘/@#"

    /** Punctuation that must not be pushed away from the caret by a trailing space. */
    private const val CLOSERS = ".,!?;:)]}"

    data class Plan(val leadingSpace: Boolean, val capitalise: Boolean)

    /**
     * @param knownWord true for ordinary dictionary words. A mid-sentence utterance that starts with
     *   a capital ("Hey running late") is lower-cased only when its first word is known, so names and
     *   "I" keep their case.
     */
    fun plan(ctx: CursorContext, knownWord: (String) -> Boolean = { false }, firstWord: String = ""): Plan {
        val atStart = ctx.blankBefore || ctx.afterSentenceEnd
        val last = ctx.before.lastOrNull()
        val needsSpace = !atStart && last != null && !last.isWhitespace() && last !in OPENERS
        // An opener bracket or quote starts a fresh clause, so what follows is capitalised too.
        val capitalise = atStart || (last != null && last in "([{")
        return Plan(leadingSpace = needsSpace, capitalise = capitalise)
    }

    /** First word of [text]: letters and inner apostrophes only. */
    fun firstWord(text: String): String = text.takeWhile { it.isLetter() || it == '\'' || it == '\u2019' }

    /**
     * Apply [plan] to [text] and add the trailing space a finished sentence needs — unless the caret
     * already sits against punctuation. [isKnownWord] decides the mid-sentence lower-casing.
     */
    fun apply(text: String, plan: Plan, ctx: CursorContext = CursorContext.EMPTY, isKnownWord: (String) -> Boolean = { false }): String {
        if (text.isEmpty()) return text
        var t = text
        val first = firstWord(t)
        if (!plan.capitalise && first.isNotEmpty()) {
            val low = first.lowercase()
            if (low != "i" && !low.startsWith("i'") && !low.startsWith("i\u2019") && isKnownWord(low)) {
                t = low + t.substring(first.length)
            }
        }
        if (plan.capitalise) t = t.replaceFirstChar { it.uppercaseChar() }
        if (plan.leadingSpace && !t[0].isWhitespace()) t = " $t"
        val endsSentence = t.trimEnd().lastOrNull()?.let { it == '.' || it == '!' || it == '?' } == true
        if (endsSentence && !t.endsWith(" ") && (ctx.firstAfter ?: ' ') !in CLOSERS) t += " "
        return t
    }
}

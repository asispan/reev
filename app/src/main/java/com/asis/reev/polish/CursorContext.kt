package com.asis.reev.polish

/**
 * The text either side of the caret at the moment dictation starts.
 *
 * Read once per utterance: it decides the mechanical polish (spacing, capitalisation) and is the
 * context an AI transform sees. Never held across editors — see [of].
 */
data class CursorContext(val before: String, val after: String) {

    val atFieldStart: Boolean get() = before.isEmpty()

    /** The last visible character before the caret, or null when the field is empty. */
    val lastBefore: Char?
        get() = before.lastOrNull { !it.isWhitespace() }

    /** The first visible character after the caret, or null at end of field. */
    val firstAfter: Char?
        get() = after.firstOrNull { !it.isWhitespace() }

    /** True when what precedes the caret already closes a sentence. */
    val afterSentenceEnd: Boolean
        get() = lastBefore?.let { it == '.' || it == '!' || it == '?' || it == '\n' } ?: true

    /** True when the caret is literally at position 0 with nothing but whitespace before it. */
    val blankBefore: Boolean get() = before.isBlank()

    companion object {
        /** How far either side of the caret we look. Beyond this is noise no polish rule needs. */
        const val WINDOW = 2000

        val EMPTY = CursorContext("", "")

        /**
         * Slice [text] at the selection. [text] itself comes from
         * [android.view.inputmethod.InputConnection.getSurroundingText], whose start offset is
         * already applied by the caller; indexes are clamped because an editor may return a
         * selection outside the text it just handed us.
         */
        fun of(text: String, selStart: Int, selEnd: Int): CursorContext {
            if (text.isEmpty()) return EMPTY
            val start = selStart.coerceIn(0, text.length)
            val end = selEnd.coerceIn(start, text.length)
            return CursorContext(text.substring(0, start), text.substring(end))
        }
    }
}

package com.asis.reev.ui

/** Spoken names for keys, as a screen reader announces them. Pure Kotlin so it can be unit-tested. */
object KeyNames {
    /** Long-press alternatives are offered as screen-reader actions; this is how many fit. */
    const val MAX_ALTS = 10

    private val symbols = mapOf(
        "," to "Comma", "." to "Period", "?" to "Question mark", "!" to "Exclamation mark",
        "'" to "Apostrophe", "\"" to "Quotation mark", ":" to "Colon", ";" to "Semicolon",
        "-" to "Hyphen", "_" to "Underscore", "@" to "At sign", "#" to "Hash", "\$" to "Dollar",
        "&" to "Ampersand", "+" to "Plus", "(" to "Left parenthesis", ")" to "Right parenthesis",
        "/" to "Slash", "\\" to "Backslash", "*" to "Asterisk", "~" to "Tilde", "`" to "Backtick",
        "|" to "Vertical bar", "•" to "Bullet", "√" to "Square root", "π" to "Pi", "÷" to "Division sign",
        "×" to "Multiplication sign", "¶" to "Paragraph sign", "∆" to "Delta", "£" to "Pound", "€" to "Euro",
        "¥" to "Yen", "₹" to "Rupee", "¢" to "Cent", "^" to "Caret", "°" to "Degree", "=" to "Equals",
        "{" to "Left brace", "}" to "Right brace", "%" to "Percent", "‰" to "Per mille", "©" to "Copyright",
        "®" to "Registered", "™" to "Trademark", "[" to "Left bracket", "]" to "Right bracket",
        "<" to "Less than", ">" to "Greater than", "…" to "Ellipsis", "–" to "En dash", "—" to "Em dash",
        "·" to "Middle dot", "±" to "Plus minus", "★" to "Star", "†" to "Dagger", "¡" to "Inverted exclamation mark",
        "¿" to "Inverted question mark", "“" to "Left double quote", "”" to "Right double quote",
        "‘" to "Left single quote", "’" to "Right single quote",
    )

    /** Name of one character as typed: "Capital A", "Comma", or the character itself. */
    fun charName(s: String): String {
        symbols[s]?.let { return it }
        if (s.length == 1 && s[0].isLetter() && s[0].isUpperCase()) return "Capital $s"
        return s
    }

    /** The text a CHAR key types in the current state. */
    fun typed(label: String, mode: KbMode, shift: ShiftState): String =
        if (mode == KbMode.LETTERS && shift != ShiftState.OFF) label.uppercase() else label

    fun shiftName(shift: ShiftState): String = when (shift) {
        ShiftState.OFF -> "Shift"
        ShiftState.ON -> "Shift on"
        ShiftState.LOCKED -> "Caps lock on"
    }

    fun modeName(mode: KbMode): String = when (mode) {
        KbMode.LETTERS -> "Letters"
        KbMode.SYMBOLS -> "Symbols"
        KbMode.MORE -> "More symbols"
    }

    /** [enterAction] is 0 for a newline, else one of EditorInfo.IME_ACTION_* (2 go .. 6 done). */
    fun enterName(enterAction: Int): String = when (enterAction) {
        2 -> "Go"; 3 -> "Search"; 4 -> "Send"; 5 -> "Next"; 6 -> "Done"
        else -> "Enter"
    }

    fun name(k: Key, mode: KbMode, shift: ShiftState, enterAction: Int): String = when (k.type) {
        KeyType.CHAR -> charName(typed(k.label, mode, shift))
        KeyType.SHIFT -> shiftName(shift)
        KeyType.DELETE -> "Delete"
        KeyType.SPACE -> "Space"
        KeyType.ENTER -> enterName(enterAction)
        KeyType.EMOJI -> "Emoji"
        KeyType.SWITCH_IME -> "Switch keyboard"
        KeyType.SYMBOLS -> modeName(KbMode.SYMBOLS)
        KeyType.LETTERS -> modeName(KbMode.LETTERS)
        KeyType.MORE_SYMBOLS -> modeName(KbMode.MORE)
        KeyType.SPACER -> ""
    }
}

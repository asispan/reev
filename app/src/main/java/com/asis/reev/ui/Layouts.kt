package com.asis.reev.ui

enum class KeyType { CHAR, SHIFT, DELETE, SYMBOLS, LETTERS, MORE_SYMBOLS, EMOJI, SPACE, ENTER, SPACER, SWITCH_IME }

class Key(
    val type: KeyType,
    val label: String = "",
    val weight: Float = 1f,
    /** Long-press alternatives; first one is also shown as the small hint. */
    val popup: List<String> = emptyList(),
    val hint: String? = popup.firstOrNull(),
) {
    // Laid out by KeyboardView.
    var l = 0f; var t = 0f; var r = 0f; var b = 0f
    fun contains(x: Float, y: Float) = x >= l && x < r && y >= t && y < b
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
}

object Layouts {
    private fun c(s: String, vararg pop: String) = Key(KeyType.CHAR, s, popup = pop.toList())

    private val accents = mapOf(
        "a" to listOf("à", "á", "â", "ä", "æ", "ã", "å", "ā"),
        "e" to listOf("è", "é", "ê", "ë", "ē"),
        "i" to listOf("ì", "í", "î", "ï", "ī"),
        "o" to listOf("ò", "ó", "ô", "ö", "õ", "œ", "ø", "ō"),
        "u" to listOf("ù", "ú", "û", "ü", "ū"),
        "c" to listOf("ç"), "n" to listOf("ñ"), "s" to listOf("ß", "\$"), "y" to listOf("ÿ"),
    )

    private fun letterRow(chars: String, digits: String? = null) = chars.mapIndexed { i, ch ->
        val s = ch.toString()
        val pop = mutableListOf<String>()
        if (digits != null) pop += digits[i].toString()
        accents[s]?.let { pop += it }
        Key(KeyType.CHAR, s, popup = pop, hint = digits?.get(i)?.toString())
    }

    /**
     * Bottom row. With [switchKey] a keyboard-switch (globe) key sits in the bottom-left corner,
     * like Gboard, whenever the system says there's another keyboard to switch to.
     */
    fun bottomRow(symbolsMode: Boolean, switchKey: Boolean): List<Key> {
        val modeKey = if (symbolsMode) Key(KeyType.LETTERS, "ABC", if (switchKey) 1.2f else 1.5f)
        else Key(KeyType.SYMBOLS, "?123", if (switchKey) 1.2f else 1.5f)
        return listOfNotNull(
            if (switchKey) Key(KeyType.SWITCH_IME, "", 1f) else null,
            modeKey,
            Key(KeyType.CHAR, ",", 1f, popup = listOf("'", "\"", ";", ":", "-", "/"), hint = null),
            Key(KeyType.EMOJI, "", 1f),
            Key(KeyType.SPACE, "", if (switchKey) 3.6f else 4f),
            Key(KeyType.CHAR, ".", 1f, popup = listOf("?", "!", ",", "…", "@", "#", "-"), hint = null),
            Key(KeyType.ENTER, "", if (switchKey) 1.2f else 1.5f),
        )
    }

    fun letters(switchKey: Boolean): List<List<Key>> = listOf(
        letterRow("qwertyuiop", "1234567890"),
        listOf(Key(KeyType.SPACER, weight = 0.5f)) + letterRow("asdfghjkl") + listOf(Key(KeyType.SPACER, weight = 0.5f)),
        listOf(Key(KeyType.SHIFT, weight = 1.5f)) + letterRow("zxcvbnm") + listOf(Key(KeyType.DELETE, weight = 1.5f)),
        bottomRow(false, switchKey),
    )

    fun symbols(switchKey: Boolean): List<List<Key>> = listOf(
        "1234567890".map { c(it.toString()) },
        listOf(
            c("@"), c("#"), c("\$", "₹", "€", "£", "¥", "¢"), c("_"), c("&"), c("-", "–", "—", "·"),
            c("+", "±"), c("("), c(")"), c("/", "\\"),
        ),
        listOf(Key(KeyType.MORE_SYMBOLS, "=\\<", 1.5f)) +
            listOf(c("*", "★", "†"), c("\"", "“", "”"), c("'", "‘", "’"), c(":"), c(";"), c("!", "¡"), c("?", "¿")) +
            listOf(Key(KeyType.DELETE, weight = 1.5f)),
        bottomRow(true, switchKey),
    )

    fun moreSymbols(switchKey: Boolean): List<List<Key>> = listOf(
        "~`|•√π÷×¶∆".map { c(it.toString()) },
        "£€¥₹^°={}".map { c(it.toString()) } + listOf(c("%", "‰")),
        listOf(Key(KeyType.SYMBOLS, "?123", 1.5f)) +
            "\\©®™[]<".map { c(it.toString()) } + listOf(Key(KeyType.DELETE, weight = 1.5f)),
        bottomRow(true, switchKey),
    )
}

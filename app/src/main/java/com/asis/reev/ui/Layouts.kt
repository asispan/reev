package com.asis.reev.ui

enum class KeyType { CHAR, SHIFT, DELETE, SYMBOLS, LETTERS, MORE_SYMBOLS, EMOJI, SPACE, ENTER, SPACER }

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

    fun bottomRow(symbolsMode: Boolean) = listOf(
        if (symbolsMode) Key(KeyType.LETTERS, "ABC", 1.5f) else Key(KeyType.SYMBOLS, "?123", 1.5f),
        Key(KeyType.CHAR, ",", 1f, popup = listOf("'", "\"", ";", ":", "-", "/"), hint = null),
        Key(KeyType.EMOJI, "", 1f),
        Key(KeyType.SPACE, "", 4f),
        Key(KeyType.CHAR, ".", 1f, popup = listOf("?", "!", ",", "…", "@", "#", "-"), hint = null),
        Key(KeyType.ENTER, "", 1.5f),
    )

    val letters: List<List<Key>> = listOf(
        letterRow("qwertyuiop", "1234567890"),
        listOf(Key(KeyType.SPACER, weight = 0.5f)) + letterRow("asdfghjkl") + listOf(Key(KeyType.SPACER, weight = 0.5f)),
        listOf(Key(KeyType.SHIFT, weight = 1.5f)) + letterRow("zxcvbnm") + listOf(Key(KeyType.DELETE, weight = 1.5f)),
        bottomRow(false),
    )

    val symbols: List<List<Key>> = listOf(
        "1234567890".map { c(it.toString()) },
        listOf(
            c("@"), c("#"), c("\$", "₹", "€", "£", "¥", "¢"), c("_"), c("&"), c("-", "–", "—", "·"),
            c("+", "±"), c("("), c(")"), c("/", "\\"),
        ),
        listOf(Key(KeyType.MORE_SYMBOLS, "=\\<", 1.5f)) +
            listOf(c("*", "★", "†"), c("\"", "“", "”"), c("'", "‘", "’"), c(":"), c(";"), c("!", "¡"), c("?", "¿")) +
            listOf(Key(KeyType.DELETE, weight = 1.5f)),
        bottomRow(true),
    )

    val moreSymbols: List<List<Key>> = listOf(
        "~`|•√π÷×¶∆".map { c(it.toString()) },
        "£€¥₹^°={}".map { c(it.toString()) } + listOf(c("%", "‰")),
        listOf(Key(KeyType.SYMBOLS, "?123", 1.5f)) +
            "\\©®™[]<".map { c(it.toString()) } + listOf(Key(KeyType.DELETE, weight = 1.5f)),
        bottomRow(true),
    )
}

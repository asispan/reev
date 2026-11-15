package com.asis.reev.history

/** Human names for the packages dictation lands in, for labels like "Inserted in WhatsApp". */
object AppNames {
    private val known = mapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.messenger.web" to "Telegram",
        "com.google.android.apps.messaging" to "Messages",
        "com.google.android.gm" to "Gmail",
        "com.google.android.keep" to "Keep",
        "com.openai.chatgpt" to "ChatGPT",
        "com.anthropic.claude" to "Claude",
        "com.slack" to "Slack",
        "com.google.android.apps.bard" to "Gemini",
        "ai.perplexity.app.android" to "Perplexity",
        "com.android.chrome" to "the browser",
        "com.brave.browser" to "the browser",
    )

    fun of(pkg: String): String = known[pkg]
        ?: pkg.substringAfterLast('.').ifEmpty { "Reev" }.replaceFirstChar { it.uppercase() }
}

package com.asis.reev.mode

import org.json.JSONArray
import org.json.JSONObject

/** What happens to a finished transcript. */
enum class DeliveryAction(val id: String) {
    INSERT("insert"),
    INSERT_SEND("send"),
    COPY("copy"),
    HISTORY_ONLY("history");

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: INSERT
    }
}

/**
 * A dictation mode: how one app's text should be shaped and delivered.
 *
 * [apps] are package names this mode selects itself for. The mode with [fallback] set is used when
 * nothing matches. [prompt] is only consulted when an AI endpoint is configured; without one a mode
 * still works, it just delivers the locally cleaned transcript.
 */
data class Mode(
    val id: String,
    val name: String,
    val prompt: String = "",
    val action: DeliveryAction = DeliveryAction.INSERT,
    val apps: List<String> = emptyList(),
    val fallback: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("prompt", prompt); put("action", action.id)
        put("apps", JSONArray(apps)); put("fallback", fallback)
    }

    companion object {
        fun from(o: JSONObject): Mode = Mode(
            id = o.optString("id").ifEmpty { "mode-" + System.nanoTime() },
            name = o.optString("name").ifEmpty { "Mode" },
            prompt = o.optString("prompt"),
            action = DeliveryAction.from(o.optString("action")),
            apps = o.optJSONArray("apps")?.let { a -> List(a.length()) { a.optString(it) } }?.filter { it.isNotEmpty() } ?: emptyList(),
            fallback = o.optBoolean("fallback"),
        )

        /** Three shipped modes. The default is the fallback; the other two claim chat and AI apps. */
        fun builtIns(): List<Mode> = listOf(
            Mode(
                id = "default", name = "Default mode", fallback = true,
                prompt = "",
            ),
            Mode(
                id = "messages", name = "Messages", action = DeliveryAction.INSERT,
                apps = listOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging"),
                prompt = "Rewrite the dictated text as a natural chat message: drop filler words and false " +
                    "starts, fix punctuation. Keep the original language, greeting, wording and details. " +
                    "Output only the message.",
            ),
            Mode(
                id = "prompts", name = "AI prompts", action = DeliveryAction.INSERT,
                apps = listOf("com.openai.chatgpt", "com.anthropic.claude", "com.google.android.apps.bard", "ai.perplexity.app.android"),
                prompt = "The dictated text is a prompt the speaker is composing for an AI assistant. Clean " +
                    "it up: drop filler words and false starts, fix grammar and punctuation. Keep the original " +
                    "language, wording and details. Never write a new prompt about the text or add instructions " +
                    "of your own. Output only the cleaned-up text.",
            ),
        )
    }
}

/** Resolves the mode for an editor. Pure: the store is injected, so this is unit-testable. */
object ModeResolver {
    fun resolve(modes: List<Mode>, packageName: String?, overrideId: String?): Mode {
        overrideId?.let { id -> modes.firstOrNull { it.id == id }?.let { return it } }
        packageName?.let { pkg ->
            modes.firstOrNull { it.apps.any { a -> a.equals(pkg, ignoreCase = true) } }?.let { return it }
        }
        return modes.firstOrNull { it.fallback } ?: modes.firstOrNull() ?: Mode.builtIns().first()
    }
}

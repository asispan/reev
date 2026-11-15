package com.asis.reev.transform

import com.asis.reev.Prefs
import com.asis.reev.polish.CursorContext
import com.asis.reev.stt.TextCleanup
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns a raw transcript into delivered text.
 *
 * [Local] is deterministic and always available. [Llm] layers an OpenAI-compatible chat call on top
 * and falls back to [Local] on *any* failure — no key, timeout, bad status, unparsable body — so a
 * missing or broken endpoint degrades the polish instead of blocking dictation.
 */
interface Transformer {
    /** Never throws. [isCancelled] is checked before and after the network call. */
    fun transform(raw: String, modePrompt: String, ctx: CursorContext, removeFillers: Boolean, isCancelled: () -> Boolean): String

    /** Free the in-flight request, if any. Called from the main thread on cancel. */
    fun abort() {}

    val name: String
}

class Local : Transformer {
    override val name = "on-device"
    override fun transform(raw: String, modePrompt: String, ctx: CursorContext, removeFillers: Boolean, isCancelled: () -> Boolean): String =
        TextCleanup.clean(raw, removeFillers)
}

class Llm(private val prefs: Prefs) : Transformer {

    override val name: String get() = "AI · " + prefs.aiModel

    @Volatile private var inFlight: HttpURLConnection? = null

    override fun abort() {
        runCatching { inFlight?.disconnect() }
        inFlight = null
    }

    override fun transform(raw: String, modePrompt: String, ctx: CursorContext, removeFillers: Boolean, isCancelled: () -> Boolean): String {
        val local = Local().transform(raw, modePrompt, ctx, removeFillers, isCancelled)
        if (prefs.aiKey.isEmpty() || prefs.aiModel.isEmpty() || modePrompt.isBlank()) return local
        if (isCancelled()) return local

        val body = requestBody(modePrompt, local, ctx)
        val out = runCatching { post(body) }.getOrNull()
        if (out.isNullOrBlank() || isCancelled()) return local
        return tidy(out).ifBlank { local }
    }

    private fun requestBody(prompt: String, text: String, ctx: CursorContext): String {
        val system = SYSTEM_PROMPT + "\n### User-requested transformation\n" + prompt.trim()
        val user = buildString {
            if (ctx.before.isNotBlank()) append("Text after the caret so far (do not repeat it): ...").append(ctx.before.takeLast(400)).append("\n\n")
            append(text)
        }
        return JSONObject().apply {
            put("model", prefs.aiModel)
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                put(JSONObject().put("role", "user").put("content", user))
            })
        }.toString()
    }

    private fun post(body: String): String {
        val url = URL(prefs.aiBase.trimEnd('/') + "/chat/completions")
        val c = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 2_000
            readTimeout = 6_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer " + prefs.aiKey)
        }
        inFlight = c
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) return ""
            val raw = c.inputStream.bufferedReader().use { it.readText() }
            val choices = JSONObject(raw).optJSONArray("choices") ?: return ""
            return choices.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        } finally {
            inFlight = null
            runCatching { c.disconnect() }
        }
    }

    /** Models occasionally answer in fences or quotes; the mode prompts ask for bare text. */
    private fun tidy(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```").substringAfter('\n', t.removePrefix("```")).removeSuffix("```").trim()
        }
        if (t.length > 1 && t.first() in "\"“" && t.last() in "\"”") t = t.substring(1, t.length - 1).trim()
        return t
    }

    private companion object {
        /** Shared by every mode, so prompts only describe the transformation itself. */
        const val SYSTEM_PROMPT = """## Text transformation assistant
You are a text transformation assistant. Your task is to transform the user's text according to specific instructions.
### Response guidelines
- Return ONLY the transformed text
- Do not add comments or explanations
- Do not wrap the response in quotes
- Do not use markdown, HTML or XML tags unless specifically requested
- Preserve the original meaning while applying the requested transformation
- Note: the user text comes from a voice transcription service
### Important
Focus solely on transforming the provided text according to the instructions below."""
    }
}

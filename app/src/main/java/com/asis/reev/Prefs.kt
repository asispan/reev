package com.asis.reev

import android.content.Context
import android.content.SharedPreferences

/** Engine ids. LOCAL runs NVIDIA Parakeet on-device via sherpa-onnx. */
enum class Engine(val id: String, val title: String, val short: String) {
    LOCAL("local", "NVIDIA Parakeet (on-device)", "Parakeet"),
    ELEVENLABS("elevenlabs", "ElevenLabs Scribe", "ElevenLabs"),
    SARVAM("sarvam", "Sarvam", "Sarvam"),
    SMALLEST("smallest", "Smallest AI Pulse", "Smallest"),
    CARTESIA("cartesia", "Cartesia Ink", "Cartesia");

    companion object {
        fun from(id: String?) = entries.firstOrNull { it.id == id } ?: LOCAL
    }
}

class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("reev", Context.MODE_PRIVATE)

    var engine: Engine
        get() = Engine.from(sp.getString("engine", Engine.LOCAL.id))
        set(v) {
            val ed = sp.edit().putString("engine", v.id)
            if (v != Engine.LOCAL) ed.putString("cloud_engine", v.id)
            ed.apply()
        }

    /**
     * The cloud provider used when dictation is switched to "API keys": the last one picked, else the first
     * with a key saved, else Sarvam. Switching between on-device and cloud only changes [engine]; the
     * downloaded model and the saved keys are left alone.
     */
    val cloudEngine: Engine
        get() = Engine.from(sp.getString("cloud_engine", null)).takeIf { it != Engine.LOCAL }
            ?: Engine.entries.firstOrNull { it != Engine.LOCAL && apiKey(it).isNotEmpty() }
            ?: Engine.SARVAM

    /**
     * API keys are stored encrypted (see [KeyVault]) under "ekey_<engine>". A plaintext "key_<engine>" left by
     * an older build or by tools/inject_keys.py is encrypted on first read and the plaintext removed.
     */
    fun apiKey(e: Engine): String = synchronized(KEY_LOCK) {
        val field = "ekey_${e.id}"
        sp.getString(field, null)?.let { blob ->
            val k = KeyVault.decrypt(blob, field)
            if (k == null) sp.edit().remove(field).commit()   // unrecoverable: drop it, the user re-enters the key
            return@synchronized k?.trim() ?: ""
        }
        val legacy = sp.getString("key_${e.id}", null)?.trim()
        if (legacy.isNullOrEmpty()) return@synchronized ""
        // Migrate in one transaction: encrypted copy in, plaintext out.
        val ok = sp.edit().putString(field, KeyVault.encrypt(legacy, field)).remove("key_${e.id}").commit()
        if (ok) legacy else ""
    }

    fun setApiKey(e: Engine, v: String): Unit = synchronized(KEY_LOCK) {
        val t = v.trim()
        val field = "ekey_${e.id}"
        val ed = sp.edit().remove("key_${e.id}")
        if (t.isEmpty()) ed.remove(field) else ed.putString(field, KeyVault.encrypt(t, field))
        ed.commit()
    }

    companion object {
        /** Process-wide: the IME and the settings screen each hold their own Prefs instance. */
        private val KEY_LOCK = Any()
        const val AI_FIELD = "ekey_ai"
    }

    var haptics: Boolean
        get() = sp.getBoolean("haptics", true)
        set(v) = sp.edit().putBoolean("haptics", v).apply()

    var sound: Boolean
        get() = sp.getBoolean("sound", false)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var autoCap: Boolean
        get() = sp.getBoolean("autocap", true)
        set(v) = sp.edit().putBoolean("autocap", v).apply()

    var doubleSpacePeriod: Boolean
        get() = sp.getBoolean("dsp", true)
        set(v) = sp.edit().putBoolean("dsp", v).apply()

    /** Tap mic -> start recording right away (hands-free latch) instead of waiting for a hold. */
    var autoStartVoice: Boolean
        get() = sp.getBoolean("autostart_voice", false)
        set(v) = sp.edit().putBoolean("autostart_voice", v).apply()

    var removeFillers: Boolean
        get() = sp.getBoolean("remove_fillers", true)
        set(v) = sp.edit().putBoolean("remove_fillers", v).apply()

    var autocorrect: Boolean
        get() = sp.getBoolean("autocorrect", true)
        set(v) = sp.edit().putBoolean("autocorrect", v).apply()

    var localModel: String
        get() = sp.getString("local_model", "parakeet-ultra") ?: "parakeet-ultra"
        set(v) = sp.edit().putString("local_model", v).apply()

    /** "system" | "light" | "dark" */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit().putString("theme", v).apply()

    var glideTyping: Boolean
        get() = sp.getBoolean("glide", true)
        set(v) = sp.edit().putBoolean("glide", v).apply()

    /** Hinglish (romanised Hindi) words in suggestions, autocorrect and glide. */
    var hinglish: Boolean
        get() = sp.getBoolean("hinglish", true)
        set(v) = sp.edit().putBoolean("hinglish", v).apply()

    /** Fix the previous word once the next one is typed ("well see" -> "we'll see"). */
    var contextFix: Boolean
        get() = sp.getBoolean("context_fix", true)
        set(v) = sp.edit().putBoolean("context_fix", v).apply()

    var recentEmoji: String
        get() = sp.getString("recent_emoji", "😂 ❤️ 👍 🙏 😊 🔥 😍 🎉") ?: ""
        set(v) = sp.edit().putString("recent_emoji", v).apply()

    /** Space before a dictation that starts mid-sentence, and the mirror of smart capitalisation. */
    var smartSpacing: Boolean
        get() = sp.getBoolean("smart_spacing", true)
        set(v) = sp.edit().putBoolean("smart_spacing", v).apply()

    /** First successful dictation already happened; the practice hint is not shown again. */
    var practiced: Boolean
        get() = sp.getBoolean("practiced", false)
        set(v) = sp.edit().putBoolean("practiced", v).apply()

    /** Keep transcripts in the history list. Off means nothing is written. */
    var historyEnabled: Boolean
        get() = sp.getBoolean("history", true)
        set(v) = sp.edit().putBoolean("history", v).apply()

    /** Sticky mode chosen from the voice panel; null means "pick by app". */
    var modeOverride: String?
        get() = sp.getString("mode_override", null)
        set(v) = sp.edit().putString("mode_override", v).apply()

    /** OpenAI-compatible endpoint used by modes with a prompt. Empty model or key means local only. */
    var aiBase: String
        get() = sp.getString("ai_base", "https://api.openai.com/v1") ?: ""
        set(v) = sp.edit().putString("ai_base", v.trim()).apply()

    var aiModel: String
        get() = sp.getString("ai_model", "") ?: ""
        set(v) = sp.edit().putString("ai_model", v.trim()).apply()

    /** Encrypted like the STT keys, under one shared "ekey_ai" slot. */
    var aiKey: String
        get() = synchronized(KEY_LOCK) {
            val blob = sp.getString(AI_FIELD, null) ?: return ""
            KeyVault.decrypt(blob, AI_FIELD)?.trim() ?: ""
        }
        set(v) {
            synchronized(KEY_LOCK) {
                val t = v.trim()
                val ed = sp.edit()
                if (t.isEmpty()) ed.remove(AI_FIELD) else ed.putString(AI_FIELD, KeyVault.encrypt(t, AI_FIELD))
                ed.apply()
            }
        }
}

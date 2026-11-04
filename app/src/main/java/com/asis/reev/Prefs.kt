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
        set(v) = sp.edit().putString("engine", v.id).apply()

    /**
     * API keys are stored encrypted (see [KeyVault]) under "ekey_<engine>". A plaintext "key_<engine>" left by
     * an older build or by tools/inject_keys.py is encrypted on first read and the plaintext removed.
     */
    @Synchronized fun apiKey(e: Engine): String {
        val field = "ekey_${e.id}"
        sp.getString(field, null)?.let { blob ->
            val k = KeyVault.decrypt(blob, field)
            if (k == null) sp.edit().remove(field).commit()   // unrecoverable: drop it, the user re-enters the key
            return k?.trim() ?: ""
        }
        val legacy = sp.getString("key_${e.id}", null)?.trim()
        if (!legacy.isNullOrEmpty()) {
            // Migrate: commit the encrypted copy first, and only then delete the plaintext.
            val ok = sp.edit().putString(field, KeyVault.encrypt(legacy, field)).commit()
            if (ok) sp.edit().remove("key_${e.id}").commit()
            return legacy
        }
        return ""
    }

    @Synchronized fun setApiKey(e: Engine, v: String) {
        val t = v.trim()
        val field = "ekey_${e.id}"
        val ed = sp.edit().remove("key_${e.id}")
        if (t.isEmpty()) ed.remove(field) else ed.putString(field, KeyVault.encrypt(t, field))
        ed.apply()
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
}

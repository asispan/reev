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

    fun apiKey(e: Engine): String = sp.getString("key_${e.id}", "")?.trim() ?: ""
    fun setApiKey(e: Engine, v: String) = sp.edit().putString("key_${e.id}", v.trim()).apply()

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

    var recentEmoji: String
        get() = sp.getString("recent_emoji", "😂 ❤️ 👍 🙏 😊 🔥 😍 🎉") ?: ""
        set(v) = sp.edit().putString("recent_emoji", v).apply()
}

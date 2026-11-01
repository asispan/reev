package com.asis.reev.stt

import android.content.Context
import com.asis.reev.Engine
import com.asis.reev.Prefs
import okhttp3.Request
import okhttp3.WebSocket
import org.json.JSONObject

// ---------------------------------------------------------------- ElevenLabs Scribe v2 realtime
class ElevenLabsStt(private val key: String) : SttEngine {
    override fun notReadyReason() = if (key.isEmpty()) "Add an ElevenLabs API key in settings" else null
    override fun warmUp() = Net.warm("https://api.elevenlabs.io/")
    override fun start(cb: SttCallback): SttSession = object : WsSession(cb, "ElevenLabs") {
        val committed = ArrayList<String>()
        var live = ""
        override fun request() = Request.Builder()
            .url("wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_16000&language_code=en&commit_strategy=manual")
            .header("xi-api-key", key).build()
        override fun sendAudio(ws: WebSocket, pcm: ShortArray) {
            ws.send(JSONObject().put("message_type", "input_audio_chunk").put("audio_base_64", b64(pcm))
                .put("commit", false).put("sample_rate", 16000).toString())
        }
        override fun sendFinish(ws: WebSocket) {
            // commit must carry audio; 50 ms of silence
            ws.send(JSONObject().put("message_type", "input_audio_chunk").put("audio_base_64", b64(ShortArray(800)))
                .put("commit", true).put("sample_rate", 16000).toString())
            armGrace(6000)
        }
        override fun currentFinal() = joinText(committed + live)
        override fun onText(json: JSONObject, raw: String) {
            when (val t = json.optString("message_type")) {
                "partial_transcript" -> { live = json.optString("text"); partial(joinText(committed + live)) }
                "committed_transcript" -> {
                    committed += json.optString("text"); live = ""
                    if (finished) deliver(joinText(committed)) else partial(joinText(committed))
                }
                "session_started", "committed_transcript_with_timestamps" -> {}
                else -> if (t.contains("error", true) || json.has("error")) fail("ElevenLabs: " + (json.optString("error").ifEmpty { json.optString("message", t) }))
            }
            if (finished) armGrace(2500)
        }
    }.connect()
}

// ---------------------------------------------------------------- Cartesia Ink-2 (manual finalize)
class CartesiaStt(private val key: String) : SttEngine {
    override fun notReadyReason() = if (key.isEmpty()) "Add a Cartesia API key in settings" else null
    override fun warmUp() = Net.warm("https://api.cartesia.ai/")
    override fun start(cb: SttCallback): SttSession = object : WsSession(cb, "Cartesia") {
        val finals = StringBuilder()
        var live = ""
        override fun request() = Request.Builder()
            .url("wss://api.cartesia.ai/stt/websocket?model=ink-2&language=en&encoding=pcm_s16le&sample_rate=16000&cartesia_version=2026-08-14")
            .header("X-API-Key", key).header("Cartesia-Version", "2026-08-14").build()
        override fun sendAudio(ws: WebSocket, pcm: ShortArray) { ws.send(bin(pcm)) }
        override fun sendFinish(ws: WebSocket) { ws.send("finalize"); armGrace(6000) }
        override fun currentFinal() = (finals.toString() + live).trim()
        override fun onText(json: JSONObject, raw: String) {
            when (json.optString("type")) {
                "transcript" -> {
                    val text = json.optString("text")
                    if (json.optBoolean("is_final")) { finals.append(text); live = "" } else live = text
                    partial((finals.toString() + live).trim())
                }
                "flush_done", "done" -> if (finished) deliver(finals.toString().trim())
                "error" -> fail("Cartesia: " + json.optString("message", json.optString("error")))
            }
            if (finished) armGrace(2500)
        }
    }.connect()
}

// ---------------------------------------------------------------- Smallest AI Pulse
class SmallestStt(private val key: String) : SttEngine {
    override fun notReadyReason() = if (key.isEmpty()) "Add a Smallest AI API key in settings" else null
    override fun warmUp() = Net.warm("https://api.smallest.ai/")
    override fun start(cb: SttCallback): SttSession = object : WsSession(cb, "Smallest") {
        val finals = ArrayList<String>()
        var live = ""
        override val frameSamples = 2048 // 4096 bytes, as recommended
        override fun request() = Request.Builder()
            .url("wss://api.smallest.ai/waves/v1/stt/live?model=pulse&language=en&encoding=linear16&sample_rate=16000")
            .header("Authorization", "Bearer $key").build()
        override fun sendAudio(ws: WebSocket, pcm: ShortArray) { ws.send(bin(pcm)) }
        override fun sendFinish(ws: WebSocket) {
            ws.send(JSONObject().put("type", "close_stream").toString()); armGrace(6000)
        }
        override fun currentFinal() = joinText(finals + live)
        override fun onText(json: JSONObject, raw: String) {
            if (json.optString("status") == "error" || json.has("error")) {
                fail("Smallest: " + json.optString("message", json.optString("error"))); return
            }
            if (json.has("transcript")) {
                val text = json.optString("transcript")
                if (json.optBoolean("is_final")) { finals += text; live = "" } else live = text
                partial(joinText(finals + live))
            }
            if (json.optBoolean("is_last") && finished) { deliver(joinText(finals + live)); return }
            if (finished) armGrace(2500)
        }
    }.connect()
}

// ---------------------------------------------------------------- Sarvam realtime (manual endpointing)
class SarvamStt(private val key: String) : SttEngine {
    override fun notReadyReason() = if (key.isEmpty()) "Add a Sarvam API key in settings" else null
    override fun warmUp() = Net.warm("https://api.sarvam.ai/")
    override fun start(cb: SttCallback): SttSession = object : WsSession(cb, "Sarvam") {
        val finals = ArrayList<String>()
        var live = ""
        override fun request() = Request.Builder()
            .url("wss://api.sarvam.ai/speech-to-text-realtime/ws?language_code=en-IN&model=saaras:v3-realtime&stream_type=fast&endpointing=manual&encoding=linear16&sample_rate=16000")
            .header("Api-Subscription-Key", key).build()
        override fun onOpenSend(ws: WebSocket) { ws.send("""{"event":"speech_start"}""") }
        override fun sendAudio(ws: WebSocket, pcm: ShortArray) {
            ws.send(JSONObject().put("event", "audio_input").put("audio", b64(pcm)).toString())
        }
        override fun sendFinish(ws: WebSocket) {
            ws.send("""{"event":"speech_end"}""")
            ws.send("""{"event":"flush"}""")
            armGrace(6000)
        }
        override fun currentFinal() = joinText(finals + live)
        override fun onText(json: JSONObject, raw: String) {
            when (json.optString("event")) {
                "transcript.partial" -> { live = json.optString("text"); partial(joinText(finals + live)) }
                "transcript.final" -> {
                    finals += json.optString("text"); live = ""
                    partial(joinText(finals))
                    if (finished) armGrace(500) // allow a trailing final, then deliver
                    return
                }
                "session.end" -> if (finished) { deliver(joinText(finals + live)); return }
                "error" -> if (json.optBoolean("is_fatal", true)) { fail("Sarvam: " + json.optString("message")); return }
            }
            if (finished) armGrace(2500)
        }
    }.connect()
}

// ---------------------------------------------------------------- factory
object Engines {
    fun create(ctx: Context, e: Engine): SttEngine {
        val p = Prefs(ctx)
        return when (e) {
            Engine.LOCAL -> LocalParakeet(ctx.applicationContext, LocalModel.from(p.sp.getString("local_model", null)))
            Engine.ELEVENLABS -> ElevenLabsStt(p.apiKey(e))
            Engine.CARTESIA -> CartesiaStt(p.apiKey(e))
            Engine.SMALLEST -> SmallestStt(p.apiKey(e))
            Engine.SARVAM -> SarvamStt(p.apiKey(e))
        }
    }

    fun warm(ctx: Context, e: Engine) { runCatching { create(ctx, e).let { if (it.notReadyReason() == null) it.warmUp() } } }
}

/** Post-processing inspired by Spokenly's "Remove filler words". */
object TextCleanup {
    private val fillers = Regex("""(?i)(^|[\s,])(um+|uh+|uhm+|erm+|hmm+|mhm+|ah+)(?=[\s,.!?]|$)[,]?""")
    fun clean(s: String, removeFillers: Boolean): String {
        var t = s.trim()
        if (removeFillers) t = t.replace(fillers, "$1").replace(Regex("""\s{2,}"""), " ").replace(Regex("""\s+([,.!?])"""), "$1").trim()
        t = t.trimStart(',', ' ')
        return t
    }
}

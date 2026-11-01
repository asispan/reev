package com.asis.reev.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * NVIDIA Parakeet on-device via sherpa-onnx. Parakeet is an offline (non-streaming) transducer, so we
 * "simulate" streaming: re-decode the growing buffer every ~0.7 s for live partials, then one final decode.
 * On a Snapdragon 8 Elite-class CPU a 10 s utterance decodes in well under a second.
 */
class LocalParakeet(private val ctx: Context, private val model: LocalModel) : SttEngine {

    companion object {
        private const val TAG = "ReevLocal"
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "parakeet").apply { priority = Thread.MAX_PRIORITY } }
        @Volatile private var loaded: Pair<LocalModel, OfflineRecognizer>? = null
        @Volatile var lastLoadMs = 0L
        @Volatile var lastDecode = ""

        fun release() { worker.execute { loaded?.second?.release(); loaded = null } }
    }

    override fun notReadyReason(): String? =
        if (!ModelManager.isInstalled(ctx, model)) "Download ${model.title} in Reev settings" else null

    private fun recognizer(): OfflineRecognizer {
        loaded?.let { if (it.first == model) return it.second else { it.second.release(); loaded = null } }
        val t0 = System.currentTimeMillis()
        val d = ModelManager.dir(ctx, model).absolutePath
        val cfg = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$d/encoder.int8.onnx", decoder = "$d/decoder.int8.onnx", joiner = "$d/joiner.int8.onnx",
                ),
                tokens = "$d/tokens.txt",
                modelType = "nemo_transducer",
                numThreads = 4,
            ),
        )
        val r = OfflineRecognizer(null, cfg)
        loaded = model to r
        lastLoadMs = System.currentTimeMillis() - t0
        Log.i(TAG, "loaded ${model.id} in ${lastLoadMs} ms")
        return r
    }

    override fun warmUp() {
        if (notReadyReason() != null) return
        worker.execute { runCatching { recognizer() }.onFailure { Log.e(TAG, "warmup", it) } }
    }

    /** Long dictations: split into ~20 s windows at the quietest 40 ms frame near each boundary. */
    private fun decode(pcm: ShortArray): String {
        val win = 16000 * 20
        if (pcm.size <= 16000 * 25) return decodeOne(pcm)
        val parts = ArrayList<String>()
        var start = 0
        while (start < pcm.size) {
            var end = minOf(pcm.size, start + win)
            if (pcm.size - end < 16000 * 5) end = pcm.size
            else {
                var best = end; var bestE = Double.MAX_VALUE
                var p = end - 16000 * 2
                while (p < end + 16000 * 2 - 640) {
                    var e = 0.0; for (i in p until p + 640) e += pcm[i] * pcm[i].toDouble()
                    if (e < bestE) { bestE = e; best = p + 320 }
                    p += 320
                }
                end = best
            }
            parts += decodeOne(pcm.copyOfRange(start, end))
            start = end
        }
        return joinText(parts)
    }

    private fun decodeOne(pcm: ShortArray): String {
        if (pcm.size < 1600) return ""
        val r = recognizer()
        val s = r.createStream()
        try {
            s.acceptWaveform(Pcm.toFloats(pcm), 16000)
            // ~0.3 s tail padding helps the transducer flush the last word
            s.acceptWaveform(FloatArray(4800), 16000)
            r.decode(s)
            return r.getResult(s).text.trim()
        } finally { s.release() }
    }

    override fun start(cb: SttCallback): SttSession = object : SttSession {
        val buf = PcmBuffer()
        val busy = AtomicBoolean(false)
        @Volatile var done = false
        var lastPartialAt = 0
        val minStep = 16000 * 7 / 10 // 0.7 s of new audio between partial decodes

        override fun feed(pcm: ShortArray) {
            if (done) return
            buf.add(pcm)
            if (buf.size - lastPartialAt >= minStep && buf.size < 16000 * 25 && busy.compareAndSet(false, true)) {
                lastPartialAt = buf.size
                val snap = buf.snapshot()
                worker.execute {
                    try { if (!done) { val t = decode(snap); if (!done && t.isNotEmpty()) cb.onPartial(t) } }
                    catch (e: Throwable) { Log.e(TAG, "partial", e) }
                    finally { busy.set(false) }
                }
            }
        }

        override fun finish() {
            if (done) return
            done = true
            val snap = buf.snapshot()
            worker.execute {
                try {
                    val t0 = System.currentTimeMillis()
                    val t = decode(snap)
                    lastDecode = "final ${snap.size / 16} ms audio -> ${System.currentTimeMillis() - t0} ms"
                    Log.i(TAG, lastDecode)
                    cb.onFinal(t)
                } catch (e: Throwable) {
                    Log.e(TAG, "final", e); cb.onError("Local model error: ${e.message}")
                }
            }
        }

        override fun cancel() { done = true }
    }
}

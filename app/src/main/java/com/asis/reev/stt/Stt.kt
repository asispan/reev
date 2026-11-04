package com.asis.reev.stt

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

interface SttCallback {
    /** Live, unstable text for the whole utterance so far. */
    fun onPartial(text: String)
    /** Final text for the whole utterance. Called exactly once (possibly empty). */
    fun onFinal(text: String)
    fun onError(msg: String)
}

interface SttSession {
    fun feed(pcm: ShortArray)
    /** No more audio; produce the final transcript. */
    fun finish()
    fun cancel()
}

interface SttEngine {
    /** null if usable, else a human-readable reason (missing key/model). */
    fun notReadyReason(): String?
    fun start(cb: SttCallback): SttSession
    /** Optional: warm up (load model / open connection pool). */
    fun warmUp() {}
}

object Pcm {
    fun toBytes(s: ShortArray): ByteArray {
        val bb = ByteBuffer.allocate(s.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.asShortBuffer().put(s)
        return bb.array()
    }

    fun toFloats(s: ShortArray, n: Int = s.size): FloatArray = FloatArray(n) { s[it] / 32768f }

    fun wav(pcm: ByteArray, rate: Int = 16000): ByteArray {
        val out = ByteArrayOutputStream(pcm.size + 44)
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + pcm.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(pcm.size)
        out.write(h.array()); out.write(pcm)
        return out.toByteArray()
    }
}

/** Growable PCM buffer. */
/** Growable PCM store for one dictation, capped so a forgotten tap-to-lock can't exhaust memory. */
class PcmBuffer(private val maxSamples: Int = MAX_SAMPLES) {
    companion object { const val MAX_SAMPLES = 16000 * 60 * 5 } // 5 minutes ≈ 9.6 MB
    private var data = ShortArray(16000 * 30)
    var size = 0
        private set
    /** True once the cap is hit; further audio is dropped. */
    val full: Boolean @Synchronized get() = size >= maxSamples

    @Synchronized fun add(s: ShortArray) {
        val n = minOf(s.size, maxSamples - size)
        if (n <= 0) return
        if (size + n > data.size) data = data.copyOf(minOf(maxSamples, maxOf(data.size * 2, size + n)))
        System.arraycopy(s, 0, data, size, n); size += n
    }

    @Synchronized fun snapshot(): ShortArray = data.copyOf(size)
}

package com.asis.reev.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import kotlin.math.log10
import kotlin.math.sqrt

/** 16 kHz mono PCM16 capture on a dedicated high-priority thread. Chunks are ~40 ms. */
class MicRecorder(
    private val onChunk: (ShortArray) -> Unit,
    private val onLevel: (Float) -> Unit,
) {
    companion object { const val RATE = 16000; const val CHUNK = 640 } // 40 ms

    @Volatile private var running = false
    private var thread: Thread? = null
    private var rec: AudioRecord? = null
    private var ns: NoiseSuppressor? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK * 2 * 8))
        } catch (e: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        if (NoiseSuppressor.isAvailable()) ns = runCatching { NoiseSuppressor.create(r.audioSessionId)?.also { it.enabled = true } }.getOrNull()
        try { r.startRecording() } catch (e: Exception) { release(r); return false }
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) { release(r); return false }
        rec = r
        running = true
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(CHUNK)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n < 0) break          // ERROR_* (device gone, invalid state): stop rather than spin
                if (n == 0) continue
                val chunk = buf.copyOf(n)
                var sum = 0.0
                for (s in chunk) sum += s * s.toDouble()
                val rms = sqrt(sum / n)
                val db = 20 * log10(rms.coerceAtLeast(1.0) / 32768.0) // -90..0
                onLevel(((db + 55) / 45).toFloat().coerceIn(0f, 1f))
                onChunk(chunk)
            }
        }, "reev-mic").also { it.start() }
        return true
    }

    fun stop() {
        running = false
        val r = rec
        // stop() first: it unblocks a read() in progress, so the join below is deterministic.
        r?.let { runCatching { it.stop() } }
        thread?.join(1000)
        thread = null
        r?.let { release(it) }
        rec = null
    }

    private fun release(r: AudioRecord) {
        ns?.let { runCatching { it.release() } }; ns = null
        runCatching { r.release() }
    }
}

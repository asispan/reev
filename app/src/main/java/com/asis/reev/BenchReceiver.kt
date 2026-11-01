package com.asis.reev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import com.asis.reev.stt.Engines
import com.asis.reev.stt.SttCallback
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-only benchmark: streams a 16 kHz mono WAV through an engine at real-time pace, exactly like the mic
 * path, and logs first-partial latency and release->final latency.
 *   adb shell am broadcast -n com.asis.reev/.BenchReceiver --es engine local --es wav /sdcard/Android/data/com.asis.reev/files/test.wav
 */
class BenchReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val engine = Engine.from(intent.getStringExtra("engine"))
        val wav = File(intent.getStringExtra("wav") ?: return)
        val pace = intent.getBooleanExtra("realtime", true)
        val pending = goAsync()
        Thread {
            try { run(ctx, engine, wav, pace) } catch (e: Throwable) { Log.e(TAG, "bench failed", e) }
            pending.finish()
        }.start()
    }

    private fun run(ctx: Context, engine: Engine, wav: File, pace: Boolean) {
        val bytes = wav.readBytes()
        val pcm = ShortArray((bytes.size - 44) / 2)
        ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm)
        val e = Engines.create(ctx, engine)
        e.notReadyReason()?.let { Log.i(TAG, "${engine.id} NOT READY: $it"); return }
        val lock = Object()
        var final: String? = null
        val t0 = System.currentTimeMillis()
        var firstPartial = -1L
        var tFinish = 0L
        val s = e.start(object : SttCallback {
            override fun onPartial(text: String) {
                if (firstPartial < 0) firstPartial = System.currentTimeMillis() - t0
                Log.d(TAG, "partial: $text")
            }
            override fun onFinal(text: String) { synchronized(lock) { final = text; lock.notifyAll() } }
            override fun onError(msg: String) { synchronized(lock) { final = "ERROR: $msg"; lock.notifyAll() } }
        })
        var i = 0
        while (i < pcm.size) {
            val n = minOf(640, pcm.size - i)
            s.feed(pcm.copyOfRange(i, i + n)); i += n
            if (pace) Thread.sleep(40)
        }
        tFinish = System.currentTimeMillis()
        s.finish()
        synchronized(lock) { if (final == null) lock.wait(25000) }
        val lat = System.currentTimeMillis() - tFinish
        Log.i(TAG, "RESULT engine=${engine.id} audio=${pcm.size / 16}ms firstPartial=${firstPartial}ms releaseToFinal=${lat}ms text=\"${final}\"")
    }

    companion object { const val TAG = "ReevBench" }
}

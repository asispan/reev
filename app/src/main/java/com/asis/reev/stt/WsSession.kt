package com.asis.reev.stt

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object Net {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    /** Pre-resolve DNS + TLS so the first press connects faster. */
    fun warm(url: String) {
        Thread {
            runCatching {
                client.newCall(Request.Builder().url(url).head().build()).execute().close()
            }
        }.start()
    }
}

/**
 * Shared WebSocket plumbing for the cloud engines. Audio captured before the socket opens is queued,
 * so recording starts the instant the user presses the button.
 */
abstract class WsSession(private val cb: SttCallback, private val tag: String) : SttSession {
    private val lock = Object()
    private var ws: WebSocket? = null
    private var open = false
    private val pending = ArrayList<ShortArray>()
    @Volatile protected var finished = false
    @Volatile protected var closed = false
    private var acc = ShortArray(0)

    /** Bytes of audio per frame sent to the server. */
    protected open val frameSamples = 1600 // 100 ms

    protected abstract fun request(): Request
    protected open fun onOpenSend(ws: WebSocket) {}
    protected abstract fun sendAudio(ws: WebSocket, pcm: ShortArray)
    protected abstract fun sendFinish(ws: WebSocket)
    protected abstract fun onText(json: JSONObject, raw: String)

    fun connect(): WsSession {
        Net.client.newWebSocket(request(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                synchronized(lock) {
                    ws = webSocket; open = true
                    onOpenSend(webSocket)
                    pending.forEach { sendAudio(webSocket, it) }
                    pending.clear()
                    if (finished) { flushAcc(webSocket); sendFinish(webSocket) }
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (closed) return
                Log.d(tag, "<- ${text.take(300)}")
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                onText(j, text)
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(tag, "closing $code $reason")
                webSocket.close(1000, null)
                if (!closed) onServerClosed(code, reason)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed) return
                val body = runCatching { response?.body?.string() }.getOrNull()
                Log.e(tag, "failure ${response?.code} $body", t)
                fail(when (response?.code) {
                    401, 403 -> "$tag: invalid API key (${response.code})"
                    null -> "$tag: ${t.message ?: "network error"}"
                    else -> "$tag: HTTP ${response.code} ${body?.take(80) ?: ""}"
                })
            }
        })
        return this
    }

    /** Default: if the server closes after we finished, deliver what we have. */
    protected open fun onServerClosed(code: Int, reason: String) {
        if (finished) deliver(currentFinal()) else fail("$tag closed: $code $reason")
    }

    protected open fun currentFinal(): String = ""

    private fun flushAcc(ws: WebSocket) {
        if (acc.isNotEmpty()) { sendAudio(ws, acc); acc = ShortArray(0) }
    }

    override fun feed(pcm: ShortArray) {
        if (finished || closed) return
        synchronized(lock) {
            acc += pcm
            if (acc.size < frameSamples) return
            val frame = acc; acc = ShortArray(0)
            val w = ws
            if (open && w != null) sendAudio(w, frame) else pending += frame
        }
    }

    override fun finish() {
        synchronized(lock) {
            if (finished) return
            finished = true
            val w = ws
            if (open && w != null) { flushAcc(w); sendFinish(w) }
        }
    }

    override fun cancel() {
        closed = true
        ws?.cancel()
    }

    private var graceFuture: java.util.concurrent.ScheduledFuture<*>? = null

    /** After finish(): deliver current text if the server goes quiet for [ms]. Re-armed on every message. */
    protected fun armGrace(ms: Long) {
        graceFuture?.cancel(false)
        graceFuture = grace.schedule({ if (!closed) deliver(currentFinal()) }, ms, TimeUnit.MILLISECONDS)
    }

    companion object {
        private val grace = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
    }

    protected fun partial(t: String) { if (!closed) cb.onPartial(t) }

    protected fun deliver(t: String) {
        if (closed) return
        closed = true
        cb.onFinal(t)
        ws?.close(1000, null)
    }

    protected fun fail(msg: String) {
        if (closed) return
        closed = true
        cb.onError(msg)
        ws?.cancel()
    }

    protected fun b64(pcm: ShortArray): String = Base64.encodeToString(Pcm.toBytes(pcm), Base64.NO_WRAP)
    protected fun bin(pcm: ShortArray): ByteString = Pcm.toBytes(pcm).toByteString()
}

fun joinText(parts: List<String>): String =
    parts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

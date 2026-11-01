package com.asis.reev.stt

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** On-device Parakeet models (sherpa-onnx int8 transducer bundles hosted on Hugging Face). */
enum class LocalModel(val id: String, val title: String, val repo: String, val note: String) {
    ULTRA(
        "parakeet-ultra", "Parakeet Ultra (int8)", "mldecode/parakeet-ultra-onnx-int8",
        "Moondream's Parakeet Ultra — fine-tune of NVIDIA parakeet-tdt-0.6b-v3. Best accuracy, ~630 MB."
    ),
    V2(
        "parakeet-tdt-v2", "NVIDIA Parakeet TDT 0.6B v2 (int8)", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        "NVIDIA's English-only Parakeet, official sherpa-onnx export, ~660 MB."
    );

    val files get() = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")

    companion object { fun from(id: String?) = entries.firstOrNull { it.id == id } ?: ULTRA }
}

object ModelManager {
    fun dir(ctx: Context, m: LocalModel): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models/${m.id}")

    fun isInstalled(ctx: Context, m: LocalModel): Boolean {
        val d = dir(ctx, m)
        return m.files.all { File(d, it).let { f -> f.exists() && f.length() > 0 } }
    }

    fun delete(ctx: Context, m: LocalModel) { dir(ctx, m).deleteRecursively() }

    @Volatile var downloading: LocalModel? = null
    @Volatile private var cancelFlag = false
    fun cancel() { cancelFlag = true }

    /** Blocking download with resume. progress(bytesDone, bytesTotal). Throws on failure. */
    fun download(ctx: Context, m: LocalModel, progress: (Long, Long) -> Unit) {
        downloading = m; cancelFlag = false
        try {
            val d = dir(ctx, m).apply { mkdirs() }
            val known = sizes(m)
            val sizes = m.files.map { known[it]?.takeIf { s -> s > 0 } ?: throw RuntimeException("Missing $it in ${m.repo}") }
            val total = sizes.sum()
            var doneBefore = 0L
            m.files.forEachIndexed { i, name ->
                val out = File(d, name)
                if (out.exists() && out.length() == sizes[i]) { doneBefore += sizes[i]; progress(doneBefore, total); return@forEachIndexed }
                val part = File(d, "$name.part")
                var have = if (part.exists()) part.length() else 0L
                val conn = open(url(m, name))
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code == 200) have = 0L else if (code != 206) throw RuntimeException("HTTP $code for $name")
                conn.inputStream.use { inp ->
                    FileOutputStream(part, have > 0).use { o ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            if (cancelFlag) throw InterruptedException("Cancelled")
                            val n = inp.read(buf); if (n < 0) break
                            o.write(buf, 0, n); have += n
                            val now = System.currentTimeMillis()
                            if (now - last > 250) { progress(doneBefore + have, total); last = now }
                        }
                    }
                }
                if (part.length() != sizes[i]) throw RuntimeException("Incomplete download of $name")
                part.renameTo(out)
                doneBefore += sizes[i]
                progress(doneBefore, total)
            }
        } finally { downloading = null }
    }

    private fun url(m: LocalModel, f: String) = "https://huggingface.co/${m.repo}/resolve/main/$f"

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15000; readTimeout = 30000
        setRequestProperty("User-Agent", "Reev/0.1")
        setRequestProperty("Accept-Encoding", "identity")
    }

    /** File sizes from the Hugging Face tree API (works for LFS and small files alike). */
    private fun sizes(m: LocalModel): Map<String, Long> {
        val c = open("https://huggingface.co/api/models/${m.repo}/tree/main")
        val body = c.inputStream.bufferedReader().readText(); c.disconnect()
        val arr = org.json.JSONArray(body)
        val out = HashMap<String, Long>()
        for (i in 0 until arr.length()) { val o = arr.getJSONObject(i); out[o.getString("path")] = o.optLong("size") }
        return out
    }
}

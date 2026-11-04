package com.asis.reev.stt

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** A pinned model file: exact size and SHA-256, checked before the file is ever handed to the native runtime. */
class ModelFile(val name: String, val size: Long, val sha256: String)

/**
 * On-device Parakeet models (sherpa-onnx int8 transducer bundles hosted on Hugging Face).
 * Each model is pinned to an immutable repository commit, and every file to its SHA-256,
 * so a changed or tampered upstream file is rejected instead of loaded.
 */
enum class LocalModel(val id: String, val title: String, val repo: String, val revision: String, val note: String, val files: List<ModelFile>) {
    ULTRA(
        "parakeet-ultra", "Parakeet Ultra (int8)", "mldecode/parakeet-ultra-onnx-int8", "3282a6e32885b431c1543d58c7710e6e3412eac0",
        "Moondream's Parakeet Ultra, a further-trained NVIDIA parakeet-tdt-0.6b-v3. Best accuracy, ~630 MB. CC BY 4.0 (Moondream); ONNX int8 export by mldecode.",
        listOf(
            ModelFile("encoder.int8.onnx", 611879344, "181382735a719c75076d13658dc4418de4b566aef39935ca0f8f55da16928f4e"),
            ModelFile("decoder.int8.onnx", 12257685, "0ba8ace2de04bb2d9a6b20ed2d67138c23df4a385f268438df0ff200502de77a"),
            ModelFile("joiner.int8.onnx", 5281747, "20ae4350c2484ba607d94f08ef25ae3ead762d8aaf70753758ffcc504e255ebb"),
            ModelFile("tokens.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        )
    ),
    V2(
        "parakeet-tdt-v2", "NVIDIA Parakeet TDT 0.6B v2 (int8)", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", "1ab9323565ddb038682214b292f588070a538ce2",
        "NVIDIA's English-only Parakeet, sherpa-onnx int8 export, ~660 MB. CC BY 4.0 (NVIDIA).",
        listOf(
            ModelFile("encoder.int8.onnx", 652184296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
            ModelFile("decoder.int8.onnx", 7257753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
            ModelFile("joiner.int8.onnx", 1739080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
            ModelFile("tokens.txt", 9384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
        )
    );

    companion object { fun from(id: String?) = entries.firstOrNull { it.id == id } ?: ULTRA }
}

object ModelManager {
    /**
     * Models live in app-internal storage, which only Reev can write to. (Older builds used the app's external
     * folder, which other apps could write on Android 8–10; those files are verified and moved on next download.)
     */
    fun dir(ctx: Context, m: LocalModel): File = File(ctx.filesDir, "models/${m.id}")
    private fun legacyDir(ctx: Context, m: LocalModel): File? = ctx.getExternalFilesDir(null)?.let { File(it, "models/${m.id}") }

    /**
     * Installed = every file at its pinned size, with a marker recording the verified SHA-256 for exactly this
     * file (size + modification time). The full hash is computed when the file is written or migrated.
     */
    fun isInstalled(ctx: Context, m: LocalModel): Boolean {
        val d = dir(ctx, m)
        return m.files.all { f -> markerOk(d, f) }
    }

    fun delete(ctx: Context, m: LocalModel) { dir(ctx, m).deleteRecursively(); legacyDir(ctx, m)?.deleteRecursively() }

    private val active = AtomicReference<LocalModel?>(null)
    val downloading: LocalModel? get() = active.get()
    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    /** Observers for background downloads (screens add themselves while visible). */
    interface Listener {
        fun onProgress(m: LocalModel, done: Long, total: Long)
        fun onDone(m: LocalModel, error: String?)
    }
    private val listeners = CopyOnWriteArraySet<Listener>()
    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "reev-model-download") }

    /**
     * Starts a download on the app-scoped worker (holds only the application context). No-op if one is running.
     * On success the model is selected and warmed here, so it doesn't depend on any screen being open.
     */
    fun start(ctx: Context, m: LocalModel) {
        if (!active.compareAndSet(null, m)) return
        cancelled = false
        val app = ctx.applicationContext
        worker.execute {
            val err = try {
                download(app, m) { d, t -> listeners.forEach { l -> runCatching { l.onProgress(m, d, t) } } }
                val p = com.asis.reev.Prefs(app); p.localModel = m.id; p.engine = com.asis.reev.Engine.LOCAL
                runCatching { Engines.warm(app, com.asis.reev.Engine.LOCAL) }
                null
            } catch (e: InterruptedException) { "cancelled" } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
            finally { active.set(null) }
            listeners.forEach { l -> runCatching { l.onDone(m, err) } }
        }
    }

    private fun marker(d: File, f: ModelFile) = File(d, "${f.name}.verified")
    private fun stamp(file: File, f: ModelFile) = "${f.sha256} ${file.length()} ${file.lastModified()}"
    private fun markerOk(d: File, f: ModelFile): Boolean {
        val file = File(d, f.name)
        if (file.length() != f.size) return false
        return runCatching { marker(d, f).readText() == stamp(file, f) }.getOrDefault(false)
    }

    /**
     * Blocking download with resume, from the pinned revision. Each file is SHA-256 checked before it is
     * moved into place; a mismatch deletes it and fails. progress(bytesDone, bytesTotal). Throws on failure.
     */
    private fun download(ctx: Context, m: LocalModel, progress: (Long, Long) -> Unit) {
        val d = dir(ctx, m).apply { mkdirs() }
        val legacy = legacyDir(ctx, m)
        val total = m.files.sumOf { it.size }
        var doneBefore = 0L
        for (f in m.files) {
            val out = File(d, f.name)
            if (markerOk(d, f)) { doneBefore += f.size; progress(doneBefore, total); continue }
            marker(d, f).delete()
            val part = File(d, "${f.name}.part")
            // Reuse a complete copy from an older build (internal or legacy external folder) only if it verifies.
            val reuse = listOfNotNull(out, legacy?.let { File(it, f.name) }).firstOrNull { it.length() == f.size && sha256(it) == f.sha256 }
            if (reuse != null) {
                if (reuse !== out) { reuse.copyTo(part, overwrite = true); if (sha256(part) != f.sha256 || !part.renameTo(out)) throw RuntimeException("Couldn't move ${f.name}") }
                marker(d, f).writeText(stamp(out, f))
                doneBefore += f.size; progress(doneBefore, total); continue
            }
            out.delete()
            var have = if (part.exists()) part.length() else 0L
            if (have > f.size) { part.delete(); have = 0L }
            if (have < f.size) {
                val conn = open(url(m, f.name))
                if (have > 0) conn.setRequestProperty("Range", "bytes=$have-")
                val code = conn.responseCode
                if (code == 200) have = 0L else if (code != 206) throw RuntimeException("HTTP $code for ${f.name}")
                conn.inputStream.use { inp ->
                    FileOutputStream(part, have > 0).use { o ->
                        val buf = ByteArray(1 shl 16)
                        var last = 0L
                        while (true) {
                            if (cancelled) throw InterruptedException("Cancelled")
                            val n = inp.read(buf); if (n < 0) break
                            o.write(buf, 0, n); have += n
                            if (have > f.size) throw RuntimeException("${f.name} is larger than expected")
                            val now = System.currentTimeMillis()
                            if (now - last > 250) { progress(doneBefore + have, total); last = now }
                        }
                    }
                }
            }
            if (part.length() != f.size) throw RuntimeException("Incomplete download of ${f.name}")
            if (sha256(part) != f.sha256) { part.delete(); throw RuntimeException("${f.name} failed its integrity check") }
            if (cancelled) throw InterruptedException("Cancelled")
            out.delete()
            if (!part.renameTo(out)) throw RuntimeException("Couldn't save ${f.name}")
            marker(d, f).writeText(stamp(out, f))
            doneBefore += f.size
            progress(doneBefore, total)
        }
        legacy?.deleteRecursively()
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { inp ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                if (cancelled) throw InterruptedException("Cancelled")
                val n = inp.read(buf); if (n < 0) break; md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun url(m: LocalModel, f: String) = "https://huggingface.co/${m.repo}/resolve/${m.revision}/$f"

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 15000; readTimeout = 30000
        setRequestProperty("User-Agent", "Reev/0.1")
        setRequestProperty("Accept-Encoding", "identity")
    }
}

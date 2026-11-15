package com.asis.reev.history

import android.content.Context
import com.asis.reev.Prefs
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dictation history: one JSON object per line, newest last.
 *
 * The raw transcript is written *before* any rewrite runs, so a process death or a failed transform
 * costs the polish, never the words. Reads re-parse the file every time — the keyboard and the
 * history screen are independent lifecycles and must not share memory.
 */
class HistoryStore(private val ctx: Context) {

    data class Entry(
        val id: Long,
        val timestamp: Long,
        val app: String,
        val mode: String,
        val model: String,
        val durationMs: Long,
        val raw: String,
        val text: String,
        val status: Status,
    ) {
        enum class Status(val id: String) { PENDING("pending"), DONE("done"), FAILED("failed"), COPIED("copied");
            companion object { fun from(id: String?) = entries.firstOrNull { it.id == id } ?: DONE }
        }

        val whenLabel: String get() = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(timestamp))
        val appLabel: String get() = AppNames.of(app)
    }

    private val file = File(ctx.filesDir, "history.jsonl")

    var enabled: Boolean
        get() = Prefs(ctx).historyEnabled
        set(v) { Prefs(ctx).historyEnabled = v }

    fun all(): List<Entry> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().mapNotNull { line ->
            if (line.isBlank()) null else runCatching { fromJson(JSONObject(line)) }.getOrNull()
        }.reversed()
    }.getOrDefault(emptyList())

    fun recent(limit: Int = 200): List<Entry> = all().take(limit)

    fun add(app: String, mode: String, model: String, durationMs: Long, raw: String): Entry {
        val entry = Entry(
            id = System.currentTimeMillis(), timestamp = System.currentTimeMillis(), app = app,
            mode = mode, model = model, durationMs = durationMs, raw = raw, text = raw,
            status = Entry.Status.PENDING,
        )
        if (enabled) append(entry)
        return entry
    }

    fun update(entry: Entry, text: String = entry.text, status: Entry.Status = entry.status) {
        if (!enabled) return
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == entry.id }
        if (i >= 0) list[i] = entry.copy(text = text, status = status) else return
        rewrite(list.reversed().takeLast(MAX))
    }

    fun delete(id: Long) {
        val list = all().filter { it.id != id }
        rewrite(list.reversed())
    }

    fun clear() = runCatching { file.delete() }

    private fun append(entry: Entry) {
        val all = all().reversed().toMutableList().also { it.add(entry) }
        rewrite(if (all.size > MAX) all.takeLast(MAX) else all)
    }

    private fun rewrite(oldestFirst: List<Entry>) {
        runCatching {
            val tmp = File(file.parentFile, "history.jsonl.tmp")
            tmp.writeText(oldestFirst.joinToString("\n") { toJson(it).toString() } + "\n")
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }
    }

    private fun toJson(e: Entry) = JSONObject().apply {
        put("id", e.id); put("ts", e.timestamp); put("app", e.app); put("mode", e.mode)
        put("model", e.model); put("ms", e.durationMs); put("raw", e.raw); put("text", e.text); put("status", e.status.id)
    }

    private fun fromJson(o: JSONObject) = Entry(
        id = o.optLong("id"),
        timestamp = o.optLong("ts"),
        app = o.optString("app"),
        mode = o.optString("mode"),
        model = o.optString("model"),
        durationMs = o.optLong("ms"),
        raw = o.optString("raw"),
        text = o.optString("text"),
        status = Entry.Status.from(o.optString("status")),
    )

    private companion object { const val MAX = 200 }
}

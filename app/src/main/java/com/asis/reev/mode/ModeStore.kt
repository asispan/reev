package com.asis.reev.mode

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Persists the user's modes as one JSON array in the app's private preferences.
 *
 * The list is always non-empty and always holds exactly one fallback, so [resolve] can never return
 * null and deleting the last custom mode cannot leave dictation without a target.
 */
class ModeStore(ctx: Context) {

    private val sp: SharedPreferences = ctx.getSharedPreferences("reev", Context.MODE_PRIVATE)

    fun all(): List<Mode> {
        val raw = sp.getString(KEY, null) ?: return Mode.builtIns().also { save(it) }
        val list = runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { Mode.from(arr.getJSONObject(it)) }
        }.getOrNull()
        if (list.isNullOrEmpty()) return Mode.builtIns().also { save(it) }
        return if (list.any { it.fallback }) list else list.mapIndexed { i, m -> if (i == 0) m.copy(fallback = true) else m }
    }

    fun byId(id: String?): Mode? = all().firstOrNull { it.id == id }

    fun resolve(packageName: String?, overrideId: String? = null): Mode =
        ModeResolver.resolve(all(), packageName, overrideId)

    fun save(modes: List<Mode>) {
        sp.edit().putString(KEY, JSONArray(modes.map { it.toJson() }).toString()).apply()
    }

    fun upsert(mode: Mode) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == mode.id }
        if (i >= 0) list[i] = mode else list.add(mode)
        save(normalise(list))
    }

    fun delete(id: String) {
        val list = all().filter { it.id != id }
        save(normalise(list))
    }

    fun resetToDefaults() = save(Mode.builtIns())

    /** Exactly one fallback, and never an empty list. */
    private fun normalise(list: List<Mode>): List<Mode> {
        if (list.isEmpty()) return Mode.builtIns()
        val fallbackAt = list.indexOfFirst { it.fallback }
        val idx = if (fallbackAt >= 0) fallbackAt else 0
        return list.mapIndexed { i, m -> m.copy(fallback = i == idx) }
    }

    private companion object { const val KEY = "modes_v1" }
}

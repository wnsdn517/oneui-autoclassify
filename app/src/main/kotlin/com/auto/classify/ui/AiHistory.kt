package com.auto.classify.ui

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** What the AI said or did for past requests, kept so answers and summaries aren't lost when the screen closes. */
object AiHistory {

    private const val KEY = "ai_history"
    private const val MAX = 30

    class Entry(val time: Long, val request: String, val text: String)

    fun load(p: SharedPreferences): List<Entry> = runCatching {
        val a = JSONArray(p.getString(KEY, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Entry(o.optLong("t"), o.optString("q"), o.optString("a"))
        }
    }.getOrDefault(emptyList())

    /** @return the list with [entry] first, also stored. */
    fun add(p: SharedPreferences, entry: Entry): List<Entry> {
        val all = (listOf(entry) + load(p)).take(MAX)
        save(p, all)
        return all
    }

    fun clear(p: SharedPreferences) = save(p, emptyList())

    private fun save(p: SharedPreferences, list: List<Entry>) {
        val a = JSONArray()
        for (e in list) a.put(JSONObject().put("t", e.time).put("q", e.request).put("a", e.text))
        p.edit().putString(KEY, a.toString()).apply()
    }
}

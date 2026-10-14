package com.auto.classify.ui

import android.content.Context
import android.content.SharedPreferences
import com.auto.classify.ai.ApiLlm
import com.auto.classify.core.Config
import com.auto.classify.core.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Settings, rules and AI choices as one JSON file. The API key is left out on purpose, so a
 * shared backup never leaks it; everything else comes back with an import.
 */
object SettingsBackup {

    private const val FORMAT = "autoclassify-settings"
    private const val VERSION = 1

    /** Per-device state that must not travel between installs. */
    private val SKIP = setOf(Config.KEY_ACKED_HEALTH, ApiLlm.KEY_KEY)

    private fun skipped(k: String) = k in SKIP || k.startsWith(ApiLlm.KEY_PREFIX)

    private fun stores(context: Context): Map<String, SharedPreferences> =
        linkedMapOf("config" to Prefs.open(context), "ai" to ApiLlm.prefs(context))

    fun export(context: Context): String {
        val root = JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("app", runCatching { Updater.current(context) }.getOrDefault("?"))
        for ((name, p) in stores(context)) {
            val o = JSONObject()
            for ((k, v) in p.all) {
                if (skipped(k) || v == null) continue
                val e = JSONObject()
                when (v) {
                    is Boolean -> e.put("t", "b").put("v", v)
                    is Int -> e.put("t", "i").put("v", v)
                    is Long -> e.put("t", "l").put("v", v)
                    is Float -> e.put("t", "f").put("v", v.toDouble())
                    is String -> e.put("t", "s").put("v", v)
                    is Set<*> -> e.put("t", "S").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                }
                o.put(k, e)
            }
            root.put(name, o)
        }
        return root.toString(2)
    }

    /** @return how many values were restored; throws when the text isn't a backup. */
    fun import(context: Context, json: String): Int {
        val root = JSONObject(json)
        require(root.optString("format") == FORMAT) { "AutoClassify 백업 파일이 아니에요" }
        var count = 0
        for ((name, p) in stores(context)) {
            val o = root.optJSONObject(name) ?: continue
            val ed = p.edit()
            for (k in o.keys()) {
                if (skipped(k)) continue
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> ed.putBoolean(k, e.getBoolean("v"))
                    "i" -> ed.putInt(k, e.getInt("v"))
                    "l" -> ed.putLong(k, e.getLong("v"))
                    "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> ed.putString(k, e.getString("v"))
                    "S" -> ed.putStringSet(k, e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() })
                    else -> continue
                }
                count++
            }
            ed.commit()
        }
        return count
    }

    /**
     * Puts back only the rules and AI instructions saved with a launcher snapshot. Used when the user
     * ticks "지시도 함께 되돌리기" before a restore; everything else in the settings stays as it is.
     * @return how many instructions were written
     */
    fun importInstructions(context: Context, instructions: JSONObject): Int {
        val ed = Prefs.open(context).edit()
        var count = 0
        for (k in Config.INSTRUCTION_KEYS) {
            val e = instructions.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                "s" -> ed.putString(k, e.optString("v"))
                "S" -> ed.putStringSet(k, e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet())
                else -> continue
            }
            count++
        }
        ed.commit()
        return count
    }
}

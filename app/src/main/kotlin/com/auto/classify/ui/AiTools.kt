package com.auto.classify.ui

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.auto.classify.ai.ApiLlm
import com.auto.classify.core.Developers
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the AI may look up while it works: the real apps-screen layout, folder contents, how long
 * each app was used, app details and (when allowed) a web search. Read-only; nothing here changes
 * the phone.
 */
class AiTools(
    private val context: Context,
    private val model: AppModel,
    private val webSearch: ((String) -> String)?,
) : ApiLlm.Tools {

    override fun declarations(): JSONArray {
        val list = JSONArray()
        list.put(decl("get_layout", "The apps screen as it is right now: every page with its folders (and the apps inside) and loose apps, in order.", JSONObject(), emptyList()))
        list.put(decl("folder_contents", "Apps inside one folder of the apps screen right now.",
            props("name" to str("folder name (part of it is fine)")), listOf("name")))
        list.put(decl("app_usage", "How long apps were used (foreground time), most used first. Needs the usage-access permission.",
            props("days" to num("look back this many days, 1-60, default 7"), "limit" to num("how many apps, default 40")), emptyList()))
        list.put(decl("app_info", "Details of installed apps matching a name or package: developer, install age, usage minutes, current folder.",
            props("query" to str("app name or package, part of it is fine")), listOf("query")))
        if (webSearch != null) {
            list.put(decl("web_search", "Search the web for facts about an app or topic; returns a short answer.",
                props("query" to str("what to look up")), listOf("query")))
        }
        return list
    }

    override fun call(name: String, args: JSONObject): String = when (name) {
        "get_layout" -> layoutText()
        "folder_contents" -> folderContents(args.optString("name"))
        "app_usage" -> appUsage(args.optInt("days", 7).coerceIn(1, 60), args.optInt("limit", 40).coerceIn(1, 150))
        "app_info" -> appInfo(args.optString("query"))
        "web_search" -> webSearch?.invoke(args.optString("query")) ?: error("웹 검색을 쓸 수 없어요")
        else -> error("알 수 없는 도구: $name")
    }

    // ---- layout ---------------------------------------------------------------------------

    private fun layout(): JSONObject = model.fetchLayout() ?: error("홈에서 현재 구성을 받지 못했어요 (홈이 응답하지 않아요)")

    private fun layoutText(): String {
        val pages = layout().optJSONArray("pages") ?: return "empty"
        val sb = StringBuilder()
        for (p in 0 until pages.length()) {
            sb.append("page ").append(p + 1).append(":\n")
            val items = pages.optJSONArray(p) ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("type") == "folder") {
                    val apps = it.optJSONArray("apps") ?: JSONArray()
                    val names = (0 until minOf(apps.length(), 25)).map { k -> apps.getJSONObject(k).optString("label") }
                    sb.append("  [folder] ").append(it.optString("title")).append(" (").append(apps.length()).append("): ")
                        .append(names.joinToString(", ")).append(if (apps.length() > 25) ", …" else "").append('\n')
                } else {
                    sb.append("  ").append(it.optString("label")).append('\n')
                }
            }
        }
        return sb.toString()
    }

    private fun folderContents(name: String): String {
        val pages = layout().optJSONArray("pages") ?: return "[]"
        val out = JSONArray()
        for (p in 0 until pages.length()) {
            val items = pages.optJSONArray(p) ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("type") != "folder" || !it.optString("title").contains(name.trim(), ignoreCase = true)) continue
                val apps = it.optJSONArray("apps") ?: JSONArray()
                out.put(JSONObject().put("folder", it.optString("title")).put("page", p + 1)
                    .put("apps", JSONArray((0 until apps.length()).map { k -> apps.getJSONObject(k).optString("label") })))
            }
        }
        return if (out.length() == 0) "no folder matches '$name'" else out.toString()
    }

    /** pkg -> folder title (loose apps are absent). */
    private fun folderOf(): Map<String, String> {
        val out = HashMap<String, String>()
        val pages = runCatching { layout().optJSONArray("pages") }.getOrNull() ?: return out
        for (p in 0 until pages.length()) {
            val items = pages.optJSONArray(p) ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("type") != "folder") continue
                val apps = it.optJSONArray("apps") ?: continue
                for (k in 0 until apps.length()) out[apps.getJSONObject(k).optString("pkg")] = it.optString("title")
            }
        }
        return out
    }

    // ---- usage and app details -------------------------------------------------------------

    private fun usage(days: Int): Map<String, Pair<Long, Long>> {
        if (!usageGranted(context)) error("사용 기록 접근이 허용되지 않았어요. 설정 탭의 '앱 사용 기록 접근'에서 허용해야 해요")
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val end = System.currentTimeMillis()
        val stats = usm.queryAndAggregateUsageStats(end - days * 86_400_000L, end)
        return stats.mapValues { it.value.totalTimeInForeground to it.value.lastTimeUsed }
    }

    private fun appUsage(days: Int, limit: Int): String {
        val u = usage(days)
        val labels = launchableApps(context).toMap()
        val rows = u.entries.filter { it.key in labels && it.value.first >= 60_000 }
            .sortedByDescending { it.value.first }.take(limit)
        val out = JSONArray()
        for ((pkg, v) in rows) {
            out.put(JSONObject().put("app", labels[pkg]).put("pkg", pkg).put("minutes", v.first / 60_000)
                .put("last_used_days_ago", ((System.currentTimeMillis() - v.second) / 86_400_000L).coerceAtLeast(0)))
        }
        return JSONObject().put("days", days).put("apps", out)
            .put("note", "apps not listed were used less than a minute in this period").toString()
    }

    private fun appInfo(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) error("query가 비어 있어요")
        val pm = context.packageManager
        val matches = launchableApps(context).filter { it.second.contains(q, ignoreCase = true) || it.first.contains(q, ignoreCase = true) }.take(5)
        if (matches.isEmpty()) return "no installed app matches '$q'"
        val week = runCatching { usage(7) }.getOrNull()
        val month = runCatching { usage(30) }.getOrNull()
        val folders = folderOf()
        val out = JSONArray()
        for ((pkg, label) in matches) {
            val o = JSONObject().put("app", label).put("pkg", pkg)
                .put("developer", runCatching { Developers.of(pm, pkg) }.getOrDefault("?"))
                .put("folder", folders[pkg] ?: "(loose)")
            runCatching {
                o.put("installed_days_ago", (System.currentTimeMillis() - pm.getPackageInfo(pkg, 0).firstInstallTime) / 86_400_000L)
            }
            if (week != null) o.put("minutes_7d", (week[pkg]?.first ?: 0L) / 60_000)
            if (month != null) o.put("minutes_30d", (month[pkg]?.first ?: 0L) / 60_000)
            if (week == null) o.put("usage", "permission not granted")
            out.put(o)
        }
        return out.toString()
    }

    // ---- declaration helpers ---------------------------------------------------------------

    private fun str(desc: String) = JSONObject().put("type", "string").put("description", desc)
    private fun num(desc: String) = JSONObject().put("type", "integer").put("description", desc)

    private fun props(vararg p: Pair<String, JSONObject>) = JSONObject().apply { p.forEach { put(it.first, it.second) } }

    private fun decl(name: String, desc: String, props: JSONObject, required: List<String>): JSONObject {
        val params = JSONObject().put("type", "object").put("properties", props)
        if (required.isNotEmpty()) params.put("required", JSONArray(required))
        return JSONObject().put("name", name).put("description", desc).put("parameters", params)
    }

    companion object {
        /** True when the user allowed this app to read usage statistics. */
        fun usageGranted(context: Context): Boolean = runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
                AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        /** Short Korean label for the progress line. */
        fun label(tool: String) = when (tool) {
            "get_layout" -> "현재 구성 확인"
            "folder_contents" -> "폴더 내용 확인"
            "app_usage" -> "앱 사용량 확인"
            "app_info" -> "앱 정보 확인"
            "web_search" -> "웹 검색"
            else -> tool
        }
    }
}

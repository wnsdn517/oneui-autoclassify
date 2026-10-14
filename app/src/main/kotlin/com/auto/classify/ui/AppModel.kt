package com.auto.classify.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auto.classify.core.Classifier
import com.auto.classify.core.Config
import com.auto.classify.core.ModuleStatus
import com.auto.classify.core.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/** A backup snapshot as reported by the launcher. */
/** A launcher snapshot. [instructions] is the rules and AI instructions saved with it, null for older ones. */
data class BackupInfo(val name: String, val layout: JSONObject?, val instructions: JSONObject?)

/**
 * Screen state and the broadcast link to the hooked launcher. Lives as long as the activity;
 * Compose reads the mutable state directly.
 */
class AppModel(private val context: Context) {

    val appContext: Context get() = context

    val prefs: SharedPreferences = Prefs.open(context)

    init {
        splitRulesByMode()
    }

    // ---- rules per mode ------------------------------------------------------------------------

    /** Rule lines (no sort settings) kept for [mode]; the active mode's live in KEY_RULES. */
    fun rulesOf(mode: String): String =
        if (mode == prefs.getString(Config.KEY_MODE, Config.MODE_CATEGORY)) {
            prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!.lines().filterNot { it.trim().startsWith("!") }.joinToString("\n").trim()
        } else prefs.getString(RULES_PREFIX + mode, "")!!

    /**
     * Each way of sorting keeps its own rules: switching stores the current ones and brings
     * back the new mode's. Sort settings (! lines) are shared.
     */
    fun setMode(mode: String) {
        val old = prefs.getString(Config.KEY_MODE, Config.MODE_CATEGORY)!!
        if (old == mode) return
        val cur = prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!.lines()
        val settings = cur.filter { it.trim().startsWith("!") }
        val body = cur.filterNot { it.trim().startsWith("!") }
        val next = prefs.getString(RULES_PREFIX + mode, "")!!.lines()
        edit({
            putString(RULES_PREFIX + old, body.joinToString("\n").trim())
            putString(Config.KEY_RULES, (next + settings).filter { it.isNotBlank() }.joinToString("\n"))
            putString(Config.KEY_MODE, mode)
        })
    }

    /**
     * Saves the active mode's rules. Rule lines the user removed are dropped from every mode's
     * stored copy too, so a deleted rule doesn't come back on the next mode switch.
     */
    fun saveRules(text: String) {
        val ruleLines = { s: String -> s.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("!") && !it.startsWith("#") } }
        val kept = ruleLines(text).toSet()
        val removed = ruleLines(prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!).filter { it !in kept }.toSet()
        edit({
            putString(Config.KEY_RULES, text.trim())
            if (removed.isNotEmpty()) {
                for (m in listOf(Config.MODE_CATEGORY, Config.MODE_COLOR, Config.MODE_AI, Config.MODE_RULES)) {
                    val stored = prefs.getString(RULES_PREFIX + m, null) ?: continue
                    putString(RULES_PREFIX + m, stored.lines().filterNot { it.trim() in removed }.joinToString("\n"))
                }
            }
        })
    }

    /**
     * One-time cleanup of the single shared rule list: color rules go to the color mode, the
     * rest to the category mode, rules that only repeat the automatic result ("게임|cat:게임")
     * are dropped, and app pins stay in every mode.
     */
    private fun splitRulesByMode() {
        if (prefs.getBoolean(RULES_SPLIT, false)) return
        val colors = setOf("빨강", "주황", "노랑", "초록", "청록", "파랑", "보라", "분홍", "흑백", "단색", "투톤", "다채로움")
        val cats = com.auto.classify.core.SmartClassifier.defaultFolders().toSet() + "업무"
        val pins = mutableListOf<String>()
        val color = mutableListOf<String>()
        val category = mutableListOf<String>()
        val settings = mutableListOf<String>()
        val lines = prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!.lines().map { it.trim() }.distinct()
        for (l in lines) {
            if (l.isEmpty() || l.startsWith("#")) continue
            if (l.startsWith("!")) {
                settings.add(l)
                continue
            }
            val head = l.substringBefore('|')
            val tokens = l.substringAfter('|').split(',').map { it.trim() }
            val catTokens = tokens.filter { it.startsWith("cat:") }.map { it.substring(4) }
            when {
                tokens.all { it.matches(Regex("(?i)re:\\^[a-z0-9_.\\\\]+\\$")) } -> pins.add(l)
                tokens.size == 1 && catTokens.size == 1 && head == catTokens[0] && (head in colors || head in cats) -> Unit
                catTokens.isNotEmpty() && catTokens.all { it in colors } -> color.add(l)
                else -> category.add(l.replace("cat:업무", "cat:생산성"))
            }
        }
        val byMode = mapOf(
            Config.MODE_CATEGORY to pins + category,
            Config.MODE_COLOR to pins + color,
            Config.MODE_AI to pins,
            Config.MODE_RULES to pins + category,
        )
        val active = prefs.getString(Config.KEY_MODE, Config.MODE_CATEGORY)!!
        prefs.edit().apply {
            for ((m, r) in byMode) if (m != active) putString(RULES_PREFIX + m, r.joinToString("\n"))
            putString(Config.KEY_RULES, (byMode[active].orEmpty() + settings).joinToString("\n"))
            putBoolean(RULES_SPLIT, true)
        }.commit()
    }
    /** The launcher answered a request, so the module is certainly running there. */
    private var launcherReplied by mutableStateOf(false)

    /** Loaded into its own app by LSPosed (needs the module in its own scope), or proven by a reply. */
    val moduleActive: Boolean get() = ModuleStatus.isHooked() || launcherReplied

    private val events = ArrayDeque<String>()

    /** App-side event trail, exported with the launcher log so a silent launcher is still debuggable. */
    fun note(msg: String) {
        synchronized(events) {
            events.addLast(SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date()) + " " + msg)
            while (events.size > 100) events.removeFirst()
        }
    }

    fun eventTrail(): String = synchronized(events) { events.joinToString("\n") }
    private val main = Handler(Looper.getMainLooper())

    /** Bumped on every settings write so composables re-read prefs. */
    var settingsVersion by mutableIntStateOf(0)
        private set

    var diagnosis by mutableStateOf<Diagnosis?>(null)
        private set
    var backups by mutableStateOf<List<BackupInfo>?>(null) // null = loading
        private set
    var backupsUnavailable by mutableStateOf(false)
        private set
    var plan by mutableStateOf<JSONObject?>(null)
    /** Shown in the preview when the home screen's tile count could not be read and 4x6 was assumed. */
    var gridWarning by mutableStateOf<String?>(null)
    var folders by mutableStateOf<List<String>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    /** Set when the user taps the risk banner to see the diagnosis again. */
    var showDiagnosis by mutableStateOf(false)

    /** One-off messages for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    @Volatile
    private var lastLayout: JSONObject? = null
    @Volatile
    private var layoutLatch: java.util.concurrent.CountDownLatch? = null

    /** The apps screen as it is now, asked from the launcher; blocks (call off the main thread). */
    fun fetchLayout(): JSONObject? {
        val latch = java.util.concurrent.CountDownLatch(1)
        layoutLatch = latch
        lastLayout = null
        send("layout")
        latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
        return lastLayout
    }

    private var pendingTimeout: Runnable? = null
    private var healthTimeout: Runnable? = null
    private var logsCallback: ((String?) -> Unit)? = null
    private var logsTimeout: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val payload = intent.getStringExtra(Config.EXTRA_MESSAGE) ?: return
            when (intent.action) {
                Config.ACTION_PLAN -> {
                    clearPending()
                    plan = runCatching { JSONObject(payload) }.getOrNull()
                    gridWarning = if (plan?.optBoolean("gridAssumed") == true) GRID_WARNING else null
                    val off = pageMismatches(plan?.optJSONObject("after"))
                    if (off.isNotEmpty()) {
                        val pinned = plan?.optInt("loosePinned") ?: 0
                        val notes = plan?.optJSONObject("pinNotes")
                        val causes = ArrayList<String>()
                        notes?.keys()?.forEach { k -> causes.add((if (k == Classifier.LOOSE) "폴더 밖 앱" else "'$k'") + ": " + notes.optString(k)) }
                        val handPins = prefs.getString(Config.KEY_RULES, "")!!.lines().count { l ->
                            val t = l.substringAfter('|', "").trim()
                            !l.trim().startsWith("#") && (t.startsWith("re:^") && t.endsWith("$") || t.startsWith("="))
                        }
                        if (handPins > 0) {
                            causes.add("직접 정한 위치(규칙) ${handPins}개가 있어요. 이런 앱은 AI 배치보다 규칙이 먼저 적용돼요 (규칙에 페이지가 없으면 AI가 정한 페이지를 따라요)")
                        }
                        if (causes.isEmpty()) {
                            causes.add("계획 안에서는 밀린 곳이 없어요. 직접 정한 규칙이나 제외한 앱이 우선했거나, AI의 페이지 지정이 계획에 전달되지 않았을 수 있어요" +
                                " (페이지를 지정한 폴더 밖 앱 ${pinned}개, 폴더 ${plan?.optJSONObject("pins")?.length() ?: 0}개)")
                        }
                        remember("미리보기 점검", "AI가 정한 페이지와 다르게 배치된 항목이 있어요\n" + off.joinToString("\n") { "• $it" } +
                            "\n원인\n" + causes.joinToString("\n") { "· $it" })
                    }
                }
                Config.ACTION_BACKUPS -> {
                    backupsUnavailable = false
                    backups = runCatching {
                        val arr = JSONArray(payload)
                        (0 until arr.length()).map {
                            val o = arr.getJSONObject(it)
                            BackupInfo(o.getString("name"), o.optJSONObject("layout"), o.optJSONObject("instructions"))
                        }
                    }.getOrDefault(emptyList())
                }
                Config.ACTION_HEALTH -> {
                    healthTimeout?.let { main.removeCallbacks(it) }
                    launcherReplied = true
                    note("health 응답 수신")
                    diagnosis = Diagnosis.from(payload, disabledFeatures())
                }
                Config.ACTION_LAYOUT -> {
                    folders = folderTitles(payload)
                    lastLayout = runCatching { JSONObject(payload) }.getOrNull()
                    layoutLatch?.countDown()
                }
                Config.ACTION_LOGS -> {
                    logsTimeout?.let { main.removeCallbacks(it) }
                    logsCallback?.invoke(payload)
                    logsCallback = null
                }
                else -> {
                    clearPending()
                    messages.tryEmit(payload)
                    requestHealth() // runtime failures are recorded by the launcher
                }
            }
        }
    }

    fun start() {
        val f = IntentFilter(Config.ACTION_RESULT).apply {
            addAction(Config.ACTION_PLAN)
            addAction(Config.ACTION_BACKUPS)
            addAction(Config.ACTION_HEALTH)
            addAction(Config.ACTION_LOGS)
            addAction(Config.ACTION_LAYOUT)
        }
        context.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
        requestHealth()
        requestBackups()
        send("layout")
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        main.removeCallbacksAndMessages(null)
    }

    fun send(cmd: String, arg: String? = null) {
        val i = Intent(Config.ACTION_COMMAND).setPackage(Config.LAUNCHER_PKG).putExtra("cmd", cmd)
        if (arg != null) i.putExtra(Config.EXTRA_ARG, arg)
        context.sendBroadcast(i)
    }

    /** Sends a command whose answer comes back as a result/plan; shows a hint if none arrives. */
    fun request(cmd: String, arg: String? = null) {
        clearPending()
        busy = true
        val timeout = Runnable {
            busy = false
            note("요청 '$cmd' 응답 없음")
            // After an app update the home keeps the old hook code until it restarts, so it ignores new commands.
            messages.tryEmit("One UI 홈이 응답하지 않아요. 앱을 업데이트했다면 One UI 홈을 재시작해 주세요 (LSPosed에서 범위가 맞는지도 확인)")
            requestHealth()
        }
        pendingTimeout = timeout
        main.postDelayed(timeout, 6000)
        send(cmd, arg)
    }

    private fun clearPending() {
        busy = false
        pendingTimeout?.let { main.removeCallbacks(it) }
        pendingTimeout = null
    }

    fun requestHealth() {
        healthTimeout?.let { main.removeCallbacks(it) }
        val t = Runnable {
            note("health 응답 없음 (모듈 자체 연결=${ModuleStatus.isHooked()}, 홈 응답 이력=$launcherReplied)")
            diagnosis = Diagnosis.noReply(ModuleStatus.isHooked())
        }
        healthTimeout = t
        main.postDelayed(t, 3500)
        send("health")
    }

    fun requestBackups() {
        backups = null
        backupsUnavailable = false
        main.postDelayed({ if (backups == null) backupsUnavailable = true }, 5000)
        send("backups")
    }

    fun requestLogs(done: (String?) -> Unit) {
        logsCallback = done
        val t = Runnable {
            note("logs 응답 없음")
            logsCallback = null
            done(null)
        }
        logsTimeout = t
        main.postDelayed(t, 3000)
        send("logs")
    }

    // ---- settings --------------------------------------------------------------------------

    fun edit(block: SharedPreferences.Editor.() -> Unit, reload: Boolean = true) {
        prefs.edit().apply(block).apply()
        settingsVersion++
        if (reload) send("reload")
    }

    fun disabledFeatures(): MutableSet<String> =
        prefs.getString(Config.KEY_DISABLED, "")!!.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .toMutableSet()

    fun excluded(): Set<String> =
        prefs.getString(Config.KEY_EXCLUDED, "")!!.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setExcluded(pkg: String, exclude: Boolean) {
        val set = excluded().toMutableSet()
        if (exclude) set.add(pkg) else set.remove(pkg)
        edit({ putString(Config.KEY_EXCLUDED, set.joinToString("\n")) })
    }

    /**
     * Pins one app to [folder] (null = out of folders) with a rule at the top, replacing any
     * earlier pin for it, then plans again so the preview updates.
     */
    fun assign(pkg: String, folder: String?, replan: Boolean = true) {
        assignAll(mapOf(pkg to folder))
        send("reload")
        if (replan) request("plan")
    }

    /** One pin rule per app (replacing older pins for the same apps); does not reload. */
    fun assignAll(pins: Map<String, String?>) {
        if (pins.isEmpty()) return
        val tokens = pins.keys.associateBy { "re:^" + it.replace(".", "\\.") + "$" }
        val lines = prefs.getString(Config.KEY_RULES, "")!!.split("\n")
            .filterNot { it.substringAfter('|', "").trim() in tokens }
        val head = lines.takeWhile { it.trim().startsWith("#") }
        val rest = lines.drop(head.size)
        val added = tokens.map { (token, pkg) -> (pins[pkg]?.trim()?.ifEmpty { null } ?: Classifier.LOOSE) + "|" + token }
        prefs.edit().putString(Config.KEY_RULES, (head + added + rest).joinToString("\n")).commit()
        settingsVersion++
    }

    /** Writes a hand-arranged layout; folder changes also become rules so new installs agree. */
    fun applyLayout(original: EditorState, edited: EditorState) {
        val before = original.folderOf()
        assignAll(edited.folderOf().filter { (pkg, folder) -> before[pkg] != folder })
        send("reload")
        send("applylayout", edited.toJson().toString())
    }

    fun toast(msg: String) {
        messages.tryEmit(msg)
    }

    // ---- AI organize -----------------------------------------------------------------------

    /** Past AI replies and summaries, newest first; [aiAnswer] is the latest one for the current screen. */
    var aiHistory by mutableStateOf(AiHistory.load(prefs))
        private set
    /** Text typed in the preview's AI box but not sent yet; Apply asks once before ignoring it. */
    var aiDraft by mutableStateOf("")
    var aiAnswer by mutableStateOf<String?>(null)
        private set

    private fun remember(request: String, text: String) {
        if (text.isBlank()) return
        var full = if (toolsUsed.isEmpty()) text else text.trim() + "\n(사용한 도구: " + toolsUsed.joinToString(", ") + ")"
        currentLlm?.webFailed?.let { full = full.trim() + "\n($it)" }
        toolsUsed.clear()
        aiAnswer = full
        aiHistory = AiHistory.add(prefs, AiHistory.Entry(System.currentTimeMillis(), request.trim(), full.trim()))
    }

    fun clearAiHistory() {
        AiHistory.clear(prefs)
        aiHistory = emptyList()
        aiAnswer = null
    }

    var aiRunning by mutableStateOf(false)
        private set
    var aiError by mutableStateOf<String?>(null)
        private set
    /** While running: what the AI is doing, and the folders it has named so far (streamed). */
    var aiStage by mutableStateOf("")
        private set
    var aiFolders by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * Asks the AI to place every app by [instruction], stores the answer (and any ordering it
     * asked for), switches to AI mode and opens the preview.
     */
    fun aiOrganize(instruction: String) {
        val llm = aiLlm()
        if (llm == null) {
            aiError = "설정 탭에서 AI 연결(API 키)을 먼저 해 주세요"
            return
        }
        edit({ putString(Config.KEY_AI_REQUEST, instruction) }, reload = false)
        runAi(instruction) {
            // Apps the answer doesn't mention keep their current place, so a request about one folder leaves the rest alone.
            val now = placesOf(fetchLayout())
            com.auto.classify.ai.AiOrganizer.organize(context, llm, instruction,
                prefs.getInt(Config.KEY_MIN_APPS, 2), progress(), now)
        }
    }

    /**
     * A follow-up request on the current AI organization; it is also added to the instruction
     * so new installs follow it too.
     */
    fun aiRefine(request: String) {
        val llm = aiLlm() ?: run {
            aiError = "설정 탭에서 AI 연결(API 키)을 먼저 해 주세요"
            return
        }
        val instruction = prefs.getString(Config.KEY_AI_REQUEST, "")!!
        val assign = com.auto.classify.ai.AiOrganizer.load(prefs.getString(Config.KEY_AI_MAP, ""))
        val pages = com.auto.classify.ai.AiOrganizer.load(prefs.getString(Config.KEY_AI_PAGES, ""))
            .mapNotNull { (k, v) -> v.toIntOrNull()?.let { k to it } }.toMap()
        appendOnSuccess = request.trim()
        runAi(request) {
            com.auto.classify.ai.AiOrganizer.refine(context, llm, instruction, assign, pages, savedOrder(), request,
                prefs.getInt(Config.KEY_MIN_APPS, 2), progress())
        }
    }

    /**
     * One input for everything AI: in AI mode the first message organizes and later ones adjust
     * the result; in other modes a message about names renames the folders of [layout], anything
     * else becomes rules.
     */
    fun aiTalk(text: String, layout: JSONObject?) {
        val t = text.trim()
        if (t.isEmpty()) return
        val mode = prefs.getString(Config.KEY_MODE, Config.MODE_CATEGORY)
        when {
            mode == Config.MODE_AI && com.auto.classify.ai.AiOrganizer.load(prefs.getString(Config.KEY_AI_MAP, "")).isEmpty() -> aiOrganize(t)
            mode == Config.MODE_AI -> aiRefine(t)
            layout != null && t.contains("이름") -> aiRename(layout, t)
            else -> aiRules(t)
        }
    }

    /** A request in category/color/rules mode: the AI writes rules, which are added on top. */
    private fun aiRules(text: String) {
        val llm = aiLlm() ?: run {
            aiError = "설정 탭에서 AI 연결(API 키)을 먼저 해 주세요"
            return
        }
        aiRunning = true
        aiError = null
        aiStage = "규칙 만드는 중"
        aiFolders = emptyList()
        background {
            val r = runCatching { com.auto.classify.ai.RuleAssistant.run(context, llm, text, folders) }
            main.post {
                aiRunning = false
                r.onSuccess { res ->
                    if (res.rules.isBlank() && res.options.isEmpty() && res.memoAdd.isEmpty() && res.memoRemove.isEmpty()
                        && res.answer.isNotBlank()) {
                        remember(text, res.answer)
                        return@onSuccess
                    }
                    if (res.rules.isBlank() && res.options.isEmpty() && res.memoAdd.isEmpty() && res.memoRemove.isEmpty()) {
                        aiError = "알아듣지 못했어요" + res.unsupported.firstOrNull()?.let { " · $it" }.orEmpty()
                        return@onSuccess
                    }
                    val old = prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!
                    if (res.rules.isNotBlank()) edit({ putString(Config.KEY_RULES, mergeRules(res.rules, old)) })
                    val (changed, memoChanges) = applyExtras(res.options, res.memoAdd, res.memoRemove)
                    val added = res.rules.lines().count { it.isNotBlank() && !it.startsWith("#") }
                    toast(listOfNotNull(if (added > 0) "규칙 ${added}개 추가" else null, optionsNotice(changed)).joinToString("\n"))
                    remember(text, listOf(res.answer, if (added > 0) "규칙 ${added}개 추가" else "", changed.joinToString("\n") { "설정 변경: $it" },
                        memoChanges.joinToString("\n"), res.unsupported.joinToString("\n"))
                        .filter { it.isNotBlank() }.joinToString("\n"))
                    request("plan")
                }.onFailure { aiError = "AI 실패: ${it.message}" }
            }
        }
    }

    /**
     * Lets the AI name the folders of [layout] (the preview's result), then keeps the names:
     * AI mode rewrites its assignment, rule folders get their rule renamed, automatic category
     * and color folders get a rename entry. The preview is planned again afterwards.
     */
    fun aiRename(layout: JSONObject, style: String) {
        val llm = aiLlm() ?: run {
            aiError = "설정 탭에서 AI 연결(API 키)을 먼저 해 주세요"
            return
        }
        val folders = LinkedHashMap<String, MutableList<String>>()
        val pages = layout.optJSONArray("pages") ?: JSONArray()
        for (p in 0 until pages.length()) {
            val items = pages.optJSONArray(p) ?: continue
            for (i in 0 until items.length()) {
                val f = items.optJSONObject(i) ?: continue
                if (f.optString("type") != "folder" || f.optString("title").isBlank()) continue
                val apps = f.optJSONArray("apps") ?: JSONArray()
                val list = folders.getOrPut(f.optString("title")) { mutableListOf() }
                for (k in 0 until apps.length()) list.add(apps.optJSONObject(k)?.optString("label") ?: continue)
            }
        }
        if (folders.isEmpty()) {
            aiError = "이름을 지을 폴더가 없어요"
            return
        }
        aiRunning = true
        aiError = null
        aiStage = com.auto.classify.ai.AiOrganizer.STAGE_SEND
        aiFolders = emptyList()
        background {
            val r = runCatching { com.auto.classify.ai.AiOrganizer.nameFolders(context, llm, folders, style, progress()) }
            main.post {
                aiRunning = false
                r.onSuccess { names ->
                    if (names.isEmpty()) {
                        toast("바꿀 이름이 없어요")
                        return@onSuccess
                    }
                    applyNames(names)
                    toast("폴더 ${names.size}개 이름을 바꿨어요")
                    request("plan")
                }.onFailure { aiError = "AI 실패: ${it.message}" }
            }
        }
    }

    /** Keeps new folder names (old -> new) in whatever decides those folders. */
    private fun applyNames(names: Map<String, String>) {
        val ai = com.auto.classify.ai.AiOrganizer.load(prefs.getString(Config.KEY_AI_MAP, ""))
            .mapValues { (_, f) -> names[f] ?: f }
        val aiPages = com.auto.classify.ai.AiOrganizer.load(prefs.getString(Config.KEY_AI_PAGES, ""))
            .mapKeys { (f, _) -> names[f] ?: f }
        // Rule folders: rename the rule's folder, keeping its color and page.
        val rules = prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!.lines().joinToString("\n") { line ->
            val t = line.trim()
            if (t.startsWith("#") || t.startsWith("!")) return@joinToString line
            val item = RuleItem.parse(t) ?: return@joinToString line
            val n = names[item.folder]
            if (item.kind != 0 || n == null) line else item.apply { folder = n }.toLine()
        }
        // Automatic folders: "기본이름=새이름", replacing an older rename of the same folder.
        val renames = LinkedHashMap<String, String>()
        prefs.getString(Config.KEY_FOLDER_NAMES, "")!!.lines().forEach { l ->
            val eq = l.indexOf('=')
            if (eq > 0) renames[l.substring(0, eq).trim()] = l.substring(eq + 1).trim()
        }
        for ((old, new) in names) {
            val base = renames.entries.firstOrNull { it.value == old }?.key ?: old
            renames[base] = new
        }
        val aiColors = com.auto.classify.ai.AiOrganizer.load(prefs.getString(Config.KEY_AI_COLORS, ""))
            .mapKeys { (f, _) -> names[f] ?: f }
        val order = JSONArray(savedOrder().map { names[it] ?: it })
        edit({
            putString(Config.KEY_AI_ORDER, order.toString())
            putString(Config.KEY_AI_MAP, com.auto.classify.ai.AiOrganizer.save(ai))
            putString(Config.KEY_AI_PAGES, JSONObject(aiPages as Map<*, *>).toString())
            putString(Config.KEY_AI_COLORS, JSONObject(aiColors as Map<*, *>).toString())
            putString(Config.KEY_RULES, rules)
            putString(Config.KEY_FOLDER_NAMES, renames.entries.joinToString("\n") { "${it.key}=${it.value}" })
        })
    }

    /** Where the preview puts things differently from the pages the AI asked for, as readable lines. */
    private fun pageMismatches(after: JSONObject?): List<String> {
        if (after == null || prefs.getString(Config.KEY_MODE, "") != Config.MODE_AI) return emptyList()
        val wanted = com.auto.classify.ai.AiOrganizer.load(prefs.getString(Config.KEY_AI_PAGES, ""))
        if (wanted.isEmpty()) return emptyList()
        val assign = com.auto.classify.ai.AiOrganizer.load(prefs.getString(Config.KEY_AI_MAP, ""))
        val pages = after.optJSONArray("pages") ?: return emptyList()
        val folderPage = HashMap<String, Int>()
        val appPage = HashMap<String, Int>()
        for (p in 0 until pages.length()) {
            val items = pages.optJSONArray(p) ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("type") == "folder") folderPage[it.optString("title").trim()] = p + 1
                else appPage[it.optString("pkg")] = p + 1
            }
        }
        val out = ArrayList<String>()
        for ((name, pg) in wanted) {
            val want = pg.toIntOrNull() ?: continue
            if (name == Classifier.LOOSE) {
                val got = assign.filterValues { it == Classifier.LOOSE }.keys.mapNotNull { appPage[it] }.filter { it != want }
                if (got.isNotEmpty()) {
                    out.add("폴더 밖 앱 ${got.size}개: ${want}페이지로 정했지만 ${got.distinct().sorted().joinToString(", ")}페이지에 놓임")
                }
            } else {
                val got = folderPage[name]
                if (got != null && got != want) out.add("'$name' 폴더: ${want}페이지로 정했지만 ${got}페이지에 놓임")
            }
        }
        return out
    }

    /** Applies the settings and notes the AI asked for; returns (setting changes, note changes). */
    private fun applyExtras(options: List<String>, memoAdd: List<String>, memoRemove: List<String>): Pair<List<String>, List<String>> {
        var changed = emptyList<String>()
        var memo = emptyList<String>()
        if (options.isEmpty() && memoAdd.isEmpty() && memoRemove.isEmpty()) return changed to memo
        edit({
            changed = com.auto.classify.ai.AiOptions.apply(prefs, this, options)
            memo = com.auto.classify.ai.AiMemo.apply(prefs, this, memoAdd, memoRemove)
        })
        return changed to memo
    }

    /** What to tell the user when the AI changed settings on request; null when it changed none. */
    private fun optionsNotice(changes: List<String>): String? =
        if (changes.isEmpty()) null else "AI가 요청대로 설정을 바꿨어요\n" + changes.joinToString("\n") { "• $it" }

    /**
     * Saves the layout settings (!폴더색 …) and folder colors the AI asked for. Colors of folders
     * it renamed move to the new name; returns what changed, for the reply.
     */
    private fun applyLook(plan: com.auto.classify.ai.AiOrganizer.Plan): List<String> {
        val out = ArrayList<String>()
        var rules = prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!
        for (line in plan.settings) {
            val body = line.removePrefix("!").trim()
            val next = setDirective(rules, body.substringBefore(' '), body.substringAfter(' ').trim())
            if (next != rules) out.add("설정 변경: ${body.replaceFirst(" ", " → ")}")
            rules = next
        }
        val colors = LinkedHashMap<String, String>()
        com.auto.classify.ai.AiOrganizer.load(prefs.getString(Config.KEY_AI_COLORS, "")).forEach { (f, c) ->
            colors[plan.renamed[f] ?: f] = c
        }
        for ((f, c) in plan.colors) {
            if (colors[f] != c) out.add("폴더 색: $f → $c")
            if (com.auto.classify.core.FolderColors.parse(c) == com.auto.classify.core.FolderColors.DEFAULT) colors.remove(f) else colors[f] = c
        }
        if (out.isNotEmpty() || plan.renamed.isNotEmpty()) {
            edit({
                putString(Config.KEY_RULES, rules)
                putString(Config.KEY_AI_COLORS, JSONObject(colors as Map<*, *>).toString())
            })
        }
        return out
    }

    /** The folder order the AI chose earlier, as a list of names. */
    private fun savedOrder(): List<String> = runCatching {
        val a = JSONArray(prefs.getString(Config.KEY_AI_ORDER, "[]"))
        (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    /** Runs an AI job on a worker thread inside a foreground service, so it survives the app closing. */
    private fun background(work: () -> Unit) {
        com.auto.classify.ui.AiService.begin(context)
        Thread {
            try {
                work()
            } finally {
                com.auto.classify.ui.AiService.end(context)
            }
        }.start()
    }

    /** Restores settings from a backup file's text, then tells the launcher to reload them. */
    fun importSettings(json: String): Int {
        val n = SettingsBackup.import(context, json)
        edit({})
        requestHealth()
        return n
    }

    /** A follow-up request joins the instruction only once the AI has handled it. */
    private var appendOnSuccess: String? = null

    /** The user's AI service; retries show up as the current stage. */
    /** The service of the current request, for what it reports afterwards (search fallback). */
    private var currentLlm: com.auto.classify.ai.ApiLlm? = null

    private fun aiLlm() = com.auto.classify.ai.ApiLlm.fromPrefs(context)?.also { llm ->
        currentLlm = llm
        aiSearch = null
        aiWeb = llm.web
        llm.onRetry = java.util.function.Consumer { n -> main.post { aiStage = "다시 시도 중 ($n)" } }
        toolsUsed.clear()
        if (prefs.let { com.auto.classify.ai.ApiLlm.prefs(context).getBoolean(com.auto.classify.ai.ApiLlm.KEY_TOOLS, true) }) {
            llm.tools = AiTools(context, this, if (llm.web) { q -> llm.webSearch(q) } else null)
            llm.onTool = java.util.function.Consumer { n ->
                main.post {
                    toolsUsed.add(AiTools.label(n))
                    aiStage = AiTools.label(n) + " 중"
                }
            }
        }
        llm.onSearch = java.util.function.Consumer { q ->
            main.post {
                aiSearch = q
                aiStage = "웹 검색: $q"
            }
        }
    }

    /** Web search queries the model ran for this request, and whether it may search at all. */
    private var aiSearch: String? = null
    private var aiWeb = false
    /** Tools the AI used for the current request, shown with its answer. */
    private val toolsUsed = LinkedHashSet<String>()

    /** What the streamed answer so far says the AI is doing, from how far its JSON has come. */
    private fun streamStage(kind: String, text: String): String {
        val prefix = ""
        return when {
            text.isEmpty() -> prefix + if (aiWeb) "검색하며 생각하는 중" else "AI가 생각하는 중"
            !text.contains("\"folders\"") -> prefix + "답변 쓰는 중"
            Regex("\"folders\"\\s*:\\s*\\[\\s*\\]").containsMatchIn(text) -> prefix + "폴더는 그대로 두는 중"
            text.contains("\"note\"") -> prefix + "요약 쓰는 중"
            text.contains("\"options\"") || text.contains("\"settings\"") -> prefix + "설정 확인 중"
            text.contains("\"loose\"") -> prefix + "폴더 밖 앱 정리 중"
            else -> {
                val n = FOLDER_NAME.findAll(text).map { it.groupValues[1] }.distinct().count()
                prefix + if (n == 0) "폴더 구성 시작" else "폴더 구성 중 (${n}개)"
            }
        }
    }

    private fun progress() = com.auto.classify.ai.AiOrganizer.Progress { stage, text ->
        val names = if (text.isEmpty()) null else FOLDER_NAME.findAll(text).map { it.groupValues[1] }.distinct().toList()
        main.post {
            val base = when (stage) {
                com.auto.classify.ai.AiUsage.KIND_ORGANIZE -> streamStage(stage, text)
                com.auto.classify.ai.AiUsage.KIND_NAMES -> if (text.isEmpty()) "이름 고민하는 중" else "이름 짓는 중 (${names?.size ?: 0}개)"
                com.auto.classify.ai.AiOrganizer.STAGE_SEND -> streamStage(com.auto.classify.ai.AiUsage.KIND_ORGANIZE, "")
                com.auto.classify.ai.AiOrganizer.STAGE_LIST -> "앱 목록 읽는 중"
                com.auto.classify.ai.AiOrganizer.STAGE_CATEGORY -> "앱 분류 중"
                com.auto.classify.ai.AiOrganizer.STAGE_DEV -> "앱 만든 곳 확인 중"
                else -> stage
            }
            aiStage = aiSearch?.let { "$base · 웹 검색함: $it" } ?: base
            if (names != null) aiFolders = names
        }
    }

    /** Runs an AI organize step off the main thread, then saves its answer and opens the preview. */
    private fun runAi(asked: String, work: () -> com.auto.classify.ai.AiOrganizer.Plan) {
        aiRunning = true
        aiError = null
        aiAnswer = null
        aiStage = com.auto.classify.ai.AiOrganizer.STAGE_LIST
        aiFolders = emptyList()
        background {
            val r = runCatching(work)
            main.post {
                aiRunning = false
                val append = appendOnSuccess
                appendOnSuccess = null
                r.onSuccess { plan ->
                    if (append != null) {
                        val ins = prefs.getString(Config.KEY_AI_REQUEST, "")!!
                        edit({ putString(Config.KEY_AI_REQUEST, (ins.trim() + "\n" + append).trim()) }, reload = false)
                    }
                    val (changed, memoChanges) = applyExtras(plan.options, plan.memoAdd, plan.memoRemove)
                    optionsNotice(changed)?.let { toast(it) }
                    // Layout settings (!폴더색 …) and folder colors apply whether or not folders change.
                    val look = applyLook(plan)
                    if (plan.assign.isEmpty()) {
                        // No folder change: a reply to a question, a settings change, colors or a note.
                        remember(asked, listOf(plan.answer, plan.note, changed.joinToString("\n") { "설정 변경: $it" },
                            look.joinToString("\n"), memoChanges.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n"))
                        if (changed.isNotEmpty() || look.isNotEmpty()) request("plan")
                        return@onSuccess
                    }
                    setMode(Config.MODE_AI)
                    edit({
                        putString(Config.KEY_AI_MAP, com.auto.classify.ai.AiOrganizer.save(plan.assign))
                        putString(Config.KEY_AI_NOTE, plan.note)
                        putString(Config.KEY_AI_PAGES, org.json.JSONObject(plan.pages as Map<*, *>).toString())
                        putString(Config.KEY_AI_ORDER, JSONArray(plan.folderOrder).toString())
                    })
                    remember(asked, listOf(plan.answer, plan.note, changed.joinToString("\n") { "설정 변경: $it" },
                        look.joinToString("\n"), memoChanges.joinToString("\n")).filter { it.isNotBlank() }.joinToString("\n"))
                    request("plan")
                }.onFailure { aiError = "AI 실패: ${it.message}" }
            }
        }
    }

    companion object {
        /** Same wording as the self-check (Engine.checkGrid). */
        const val GRID_WARNING = "홈 화면 앱 타일 수를 감지하지 못해 4x6으로 가정했어요. 페이지가 밀리거나 의도와 다르게 배치될 수 있어요"
        const val RULES_PREFIX = "rules_"
        private const val RULES_SPLIT = "rules_split_v1"
        val FOLDER_NAME = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
    }

    /** pkg -> folder title it sits in now (Classifier.LOOSE when outside folders); null when the layout is unknown. */
    private fun placesOf(layout: JSONObject?): Map<String, String>? {
        val pages = layout?.optJSONArray("pages") ?: return null
        val out = LinkedHashMap<String, String>()
        for (p in 0 until pages.length()) {
            val items = pages.optJSONArray(p) ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                if (it.optString("type") == "folder") {
                    val apps = it.optJSONArray("apps") ?: continue
                    val title = it.optString("title").trim()
                    for (k in 0 until apps.length()) apps.optJSONObject(k)?.optString("pkg")?.takeIf { s -> s.isNotEmpty() }
                        ?.let { pkg -> out.putIfAbsent(pkg, title.ifEmpty { Classifier.LOOSE }) }
                } else it.optString("pkg").takeIf { s -> s.isNotEmpty() }?.let { pkg -> out.putIfAbsent(pkg, Classifier.LOOSE) }
            }
        }
        return out
    }

    private fun folderTitles(layoutJson: String): List<String> = runCatching {
        val out = LinkedHashSet<String>()
        val pages = JSONObject(layoutJson).optJSONArray("pages") ?: return@runCatching emptyList<String>()
        for (p in 0 until pages.length()) {
            val items = pages.optJSONArray(p) ?: continue
            for (i in 0 until items.length()) {
                val it = items.optJSONObject(i) ?: continue
                val title = it.optString("title")
                if (it.optString("type") == "folder" && title.isNotEmpty()) out.add(title)
            }
        }
        out.toList()
    }.getOrDefault(emptyList())
}

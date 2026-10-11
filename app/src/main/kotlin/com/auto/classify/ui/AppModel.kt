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
data class BackupInfo(val name: String, val layout: JSONObject?)

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
    var folders by mutableStateOf<List<String>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    /** Set when the user taps the risk banner to see the diagnosis again. */
    var showDiagnosis by mutableStateOf(false)

    /** One-off messages for the snackbar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

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
                }
                Config.ACTION_BACKUPS -> {
                    backupsUnavailable = false
                    backups = runCatching {
                        val arr = JSONArray(payload)
                        (0 until arr.length()).map {
                            val o = arr.getJSONObject(it)
                            BackupInfo(o.getString("name"), o.optJSONObject("layout"))
                        }
                    }.getOrDefault(emptyList())
                }
                Config.ACTION_HEALTH -> {
                    healthTimeout?.let { main.removeCallbacks(it) }
                    launcherReplied = true
                    note("health 응답 수신")
                    diagnosis = Diagnosis.from(payload, disabledFeatures())
                }
                Config.ACTION_LAYOUT -> folders = folderTitles(payload)
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
            messages.tryEmit("One UI 홈이 응답하지 않아요. 앱스 화면을 한 번 열어 주세요.")
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
    var aiAnswer by mutableStateOf<String?>(null)
        private set

    private fun remember(request: String, text: String) {
        if (text.isBlank()) return
        aiAnswer = text
        aiHistory = AiHistory.add(prefs, AiHistory.Entry(System.currentTimeMillis(), request.trim(), text.trim()))
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
                com.auto.classify.ai.AiOrganizer.organize(context, llm, instruction,
                    prefs.getBoolean(Config.KEY_AI_VERIFY, false), prefs.getInt(Config.KEY_MIN_APPS, 2), progress())
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
            com.auto.classify.ai.AiOrganizer.refine(context, llm, instruction, assign, pages, request,
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
                    if (res.rules.isBlank() && res.options.isEmpty() && res.answer.isNotBlank()) {
                        remember(text, res.answer)
                        return@onSuccess
                    }
                    if (res.rules.isBlank() && res.options.isEmpty()) {
                        aiError = "알아듣지 못했어요" + res.unsupported.firstOrNull()?.let { " · $it" }.orEmpty()
                        return@onSuccess
                    }
                    val old = prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!
                    var changed = emptyList<String>()
                    edit({
                        if (res.rules.isNotBlank()) putString(Config.KEY_RULES, mergeRules(res.rules, old))
                        changed = com.auto.classify.ai.AiOptions.apply(prefs, this, res.options)
                    })
                    val added = res.rules.lines().count { it.isNotBlank() && !it.startsWith("#") }
                    toast(listOfNotNull(if (added > 0) "규칙 ${added}개 추가" else null, optionsNotice(changed)).joinToString("\n"))
                    remember(text, listOf(res.answer, if (added > 0) "규칙 ${added}개 추가" else "", changed.joinToString("\n") { "설정 변경: $it" },
                        res.unsupported.joinToString("\n"))
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
        edit({
            putString(Config.KEY_AI_MAP, com.auto.classify.ai.AiOrganizer.save(ai))
            putString(Config.KEY_AI_PAGES, JSONObject(aiPages as Map<*, *>).toString())
            putString(Config.KEY_RULES, rules)
            putString(Config.KEY_FOLDER_NAMES, renames.entries.joinToString("\n") { "${it.key}=${it.value}" })
        })
    }

    /** What to tell the user when the AI changed settings on request; null when it changed none. */
    private fun optionsNotice(changes: List<String>): String? =
        if (changes.isEmpty()) null else "AI가 요청대로 설정을 바꿨어요\n" + changes.joinToString("\n") { "• $it" }

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
    private fun aiLlm() = com.auto.classify.ai.ApiLlm.fromPrefs(context)?.also { llm ->
        aiSearch = null
        aiWeb = llm.web
        llm.onRetry = java.util.function.Consumer { n -> main.post { aiStage = "다시 시도 중 ($n)" } }
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

    /** What the streamed answer so far says the AI is doing, from how far its JSON has come. */
    private fun streamStage(kind: String, text: String): String {
        val verify = kind == com.auto.classify.ai.AiUsage.KIND_VERIFY
        val prefix = if (verify) "검토 · " else ""
        return when {
            text.isEmpty() -> prefix + if (aiWeb) "검색하며 생각하는 중" else "AI가 생각하는 중"
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
                com.auto.classify.ai.AiUsage.KIND_VERIFY, com.auto.classify.ai.AiUsage.KIND_ORGANIZE -> streamStage(stage, text)
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
                    if (plan.assign.isEmpty()) {
                        // Only a reply (a question): nothing to reorganize.
                        remember(asked, plan.answer)
                        return@onSuccess
                    }
                    setMode(Config.MODE_AI)
                    var rules = prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!
                    for (line in plan.settings) {
                        val key = line.removePrefix("!").trim().substringBefore(' ')
                        rules = setDirective(rules, key, line.removePrefix("!").trim().substringAfter(' ').trim())
                    }
                    var changed = emptyList<String>()
                    edit({
                        changed = com.auto.classify.ai.AiOptions.apply(prefs, this, plan.options)
                        putString(Config.KEY_AI_MAP, com.auto.classify.ai.AiOrganizer.save(plan.assign))
                        putString(Config.KEY_AI_NOTE, plan.note)
                        putString(Config.KEY_AI_PAGES, org.json.JSONObject(plan.pages as Map<*, *>).toString())
                        putString(Config.KEY_RULES, rules)
                    })
                    optionsNotice(changed)?.let { toast(it) }
                    remember(asked, listOf(plan.answer, plan.note, changed.joinToString("\n") { "설정 변경: $it" })
                        .filter { it.isNotBlank() }.joinToString("\n"))
                    request("plan")
                }.onFailure { aiError = "AI 실패: ${it.message}" }
            }
        }
    }

    companion object {
        const val RULES_PREFIX = "rules_"
        private const val RULES_SPLIT = "rules_split_v1"
        val FOLDER_NAME = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
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

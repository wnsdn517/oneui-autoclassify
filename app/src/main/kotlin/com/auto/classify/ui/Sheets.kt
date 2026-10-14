package com.auto.classify.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.auto.classify.ClassifierProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.auto.classify.ai.ApiLlm
import com.auto.classify.ai.RuleAssistant
import com.auto.classify.core.Classifier
import com.auto.classify.core.Config
import com.auto.classify.core.SmartClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

// ---- shared ----------------------------------------------------------------------------------

@Composable
fun Hint(text: String) {
    Text(text, Modifier.padding(vertical = 4.dp), style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

/** "작업 중" text: a light sweeping across it plus running dots. */
@Composable
fun WorkingText(text: String, modifier: Modifier = Modifier) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "working")
    val x by t.animateFloat(
        -200f, 800f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1300, easing = androidx.compose.animation.core.LinearEasing)),
        label = "sweep",
    )
    val dots by t.animateFloat(
        0f, 3.99f,
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(1200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "dots",
    )
    val base = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val hi = MiuixTheme.colorScheme.primary
    val brush = androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(base, hi, base), start = androidx.compose.ui.geometry.Offset(x, 0f), end = androidx.compose.ui.geometry.Offset(x + 200f, 0f),
    )
    Text(text + ".".repeat(dots.toInt()).padEnd(3, ' '), modifier, style = MiuixTheme.textStyles.body1.copy(brush = brush))
}

/** Names that appear one by one (folders the AI has named so far). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppearingChips(names: List<String>) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        names.forEach { n ->
            key(n) {
                val shown = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                androidx.compose.animation.AnimatedVisibility(
                    shown,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
                ) {
                    Text(
                        n,
                        Modifier.clip(RoundedCornerShape(50)).background(MiuixTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MiuixTheme.textStyles.footnote1,
                    )
                }
            }
        }
    }
}

@Composable
fun ErrorText(text: String) {
    Text(text, Modifier.padding(vertical = 4.dp), style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
}

/** Segmented tabs for sheets and dialogs: no track color, so no dark strip on the sheet. */
@Composable
fun SegTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    TabRow(
        tabs, selected, onSelect, modifier,
        colors = top.yukonga.miuix.kmp.basic.TabRowDefaults.tabRowColors(
            backgroundColor = androidx.compose.ui.graphics.Color.Transparent,
            selectedBackgroundColor = MiuixTheme.colorScheme.surfaceContainerHighest,
        ),
    )
}

/** Two buttons side by side, primary on the right — at the bottom of sheets. */
@Composable
fun SheetActions(secondary: String, onSecondary: () -> Unit, primary: String, onPrimary: () -> Unit, primaryEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(secondary, onSecondary, Modifier.weight(1f))
        TextButton(primary, onPrimary, Modifier.weight(1f), enabled = primaryEnabled,
            colors = ButtonDefaults.textButtonColorsPrimary())
    }
}

/** Launchable apps as pkg -> label, sorted by label. */
fun launchableApps(context: Context): List<Pair<String, String>> {
    val pm = context.packageManager
    val seen = LinkedHashMap<String, String>()
    for (ri in pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
        seen.putIfAbsent(ri.activityInfo.packageName, ri.loadLabel(pm).toString())
    }
    val collator = java.text.Collator.getInstance(java.util.Locale.KOREAN)
    return seen.entries.map { it.key to it.value }.sortedWith { a, b -> collator.compare(a.second, b.second) }
}

fun categoriesFor(model: AppModel): List<String> =
    if (model.prefs.getString(Config.KEY_MODE, Config.MODE_CATEGORY) == Config.MODE_COLOR)
        listOf("빨강", "주황", "노랑", "초록", "청록", "파랑", "보라", "분홍", "흑백", "단색", "투톤", "다채로움")
    else SmartClassifier.defaultFolders().toList()

// ---- AI rule builder ---------------------------------------------------------------------------

private val AI_EXAMPLES = listOf(
    "게임은 2페이지 놀이 폴더로",
    "구글 앱은 구글 폴더, 삼성 앱은 삼성 폴더",
    "토스, 쿠팡, 배달의민족은 생활 폴더",
    "게임이랑 엔터 폴더 합쳐줘",
    "루팅 앱은 폴더에서 다 꺼내줘",
    "폴더 안은 색상순, 폴더를 먼저",
    "게임 폴더는 빨간색",
    "폴더는 3개 이상일 때만 만들어줘",
)

/** "말로 규칙 만들기": the built-in parser, or the AI with the user's key. */
@Composable
fun AiSheet(model: AppModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val p = model.prefs
    var request by remember { mutableStateOf("") }
    val hasKey = remember { ApiLlm.fromPrefs(context) != null }
    var engine by remember { mutableStateOf(if (hasKey && p.getString(Config.KEY_AI_ENGINE, "online") != "offline") 1 else 0) }
    var running by remember { mutableStateOf(false) }
    var streamed by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<RuleAssistant.Result?>(null) }
    var edited by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showExamples by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    WindowBottomSheet(show = true, title = "말로 규칙 만들기", onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
            SegTabs(listOf("기본", "AI"), engine, {
                engine = it
                model.edit({ putString(Config.KEY_AI_ENGINE, if (it == 0) "offline" else "online") }, reload = false)
            })
            if (engine == 1 && !hasKey) Hint("설정에서 AI를 연결하세요")
            TextField(
                value = request,
                onValueChange = { request = it },
                useLabelAsPlaceholder = true, label = "예: 게임은 2페이지 놀이 폴더로",
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                if (showExamples) "예시 접기" else "예시 보기",
                Modifier.fillMaxWidth().clickable { showExamples = !showExamples }.padding(horizontal = 4.dp, vertical = 10.dp),
                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary,
            )
            if (showExamples) {
                for (ex in AI_EXAMPLES) {
                    Text(
                        ex,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable { request = if (request.isBlank()) ex else request.trimEnd() + ", " + ex }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MiuixTheme.textStyles.body2,
                    )
                }
            }
            TextButton(
                text = if (running) "만드는 중…" else "규칙 만들기",
                onClick = {
                    val text = request.trim()
                    if (text.isEmpty()) return@TextButton
                    running = true
                    streamed = ""
                    error = null
                    scope.launch {
                        val r = runCatching {
                            withContext(Dispatchers.IO) {
                                if (engine == 0) RuleAssistant.runOffline(context, text, model.folders)
                                else RuleAssistant.run(context, ApiLlm.fromPrefs(context), text, model.folders) { t -> streamed = t }
                            }
                        }
                        running = false
                        r.onSuccess {
                            result = it
                            edited = it.rules
                            if (it.rules.isEmpty()) error = "규칙을 만들지 못했어요. 더 구체적으로 써 보세요."
                        }.onFailure { error = "실패: ${it.message}" }
                    }
                },
                enabled = !running && (engine == 0 || hasKey),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
            if (running) {
                WorkingText(if (streamed.isEmpty()) "생각하는 중" else "쓰는 중", Modifier.padding(top = 12.dp))
                if (streamed.isNotEmpty()) Text(streamed.takeLast(600), Modifier.padding(top = 6.dp),
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            error?.let { ErrorText(it) }
            result?.let { r ->
                Spacer(Modifier.height(16.dp))
                r.unsupported.forEach { ErrorText("⚠ $it") }
                if (r.dropped > 0) Hint("형식이 틀린 ${r.dropped}줄은 뺐어요")
                val err = Classifier.validate(edited)
                TextField(value = edited, onValueChange = { edited = it }, useLabelAsPlaceholder = true, label = "만들어진 규칙 (고칠 수 있어요)",
                    minLines = 4, modifier = Modifier.fillMaxWidth())
                err?.let { ErrorText(it) }
                SheetActions("전부 바꾸기", {
                    if (err == null) {
                        model.saveRules(edited)
                        model.toast("규칙을 바꿨어요. 미리보기로 확인해 보세요")
                        onClose()
                    }
                }, "추가하기", {
                    if (err == null) {
                        val old = p.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!
                        model.edit({ putString(Config.KEY_RULES, mergeRules(edited, old)) })
                        model.toast("규칙에 추가했어요. 미리보기로 확인해 보세요")
                        onClose()
                    }
                }, primaryEnabled = err == null && edited.isNotBlank())
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** New rules go first (they win); a new directive replaces the same old one. */
fun mergeRules(newRules: String, old: String): String {
    val newDirectiveKeys = newRules.lines().map { it.trim() }.filter { it.startsWith("!") }
        .map { it.removePrefix("!").trim().substringBefore(' ') }.toSet()
    val kept = old.lines().filterNot { l ->
        val t = l.trim()
        t.startsWith("!") && t.removePrefix("!").trim().substringBefore(' ') in newDirectiveKeys
    }
    // The same line twice adds nothing: keep the new copy (on top) only.
    val fresh = newRules.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return (fresh + kept.filterNot { it.trim() in fresh }).joinToString("\n").trim()
}

// ---- signals -----------------------------------------------------------------------------------

@Composable
fun SignalsSheet(model: AppModel, onClose: () -> Unit) {
    model.settingsVersion
    val on = model.prefs.getString(Config.KEY_SIGNALS, Config.DEFAULT_SIGNALS)!!.split(",").map { it.trim() }.toMutableSet()
    val items = listOf(
        Triple("roles", "앱 역할·구성요소", "브라우저·전화·카메라·키보드·Xposed 모듈 등 시스템이 아는 역할, 이름 키워드"),
        Triple("play", "Play 스토어 데이터", "인기 앱 18만 개의 실제 카테고리 (내장)"),
        Triple("model", "분류 모델", "패키지명·앱 이름으로 추정 (230만 앱 학습, Naive Bayes)"),
        Triple("declared", "앱이 선언한 카테고리", "개발자가 manifest에 적은 값"),
    )
    WindowBottomSheet(show = true, title = "분류 근거", onDismissRequest = onClose) {
        Column {
            items.forEach { (id, title, summary) ->
                model.settingsVersion
                SwitchPreference(
                    checked = id in on,
                    onCheckedChange = { v ->
                        if (v) on.add(id) else on.remove(id)
                        model.edit({ putString(Config.KEY_SIGNALS, on.joinToString(",")) })
                    },
                    title = title, summary = summary,
                )
            }
            IntSlider(model, Config.KEY_THRESHOLD, Config.DEFAULT_THRESHOLD, 30..99, "최소 확신도", "%")
            Spacer(Modifier.height(16.dp))
        }
    }
}

// ---- text editors ------------------------------------------------------------------------------

@Composable
fun TextEditSheet(
    title: String, help: String, initial: String, validate: (String) -> String?,
    onSave: (String) -> Unit, onClose: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    val err = validate(text)
    WindowBottomSheet(show = true, title = title, onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
            Hint(help)
            TextField(value = text, onValueChange = { text = it }, minLines = 8, modifier = Modifier.fillMaxWidth())
            err?.let { ErrorText(it) }
            SheetActions("취소", onClose, "저장", {
                if (err == null) {
                    onSave(text)
                    onClose()
                }
            }, primaryEnabled = err == null)
        }
    }
}

@Composable
fun RulesTextSheet(model: AppModel, onClose: () -> Unit) {
    TextEditSheet(
        "규칙 (텍스트)",
        "한 줄에 하나 · 폴더이름[@페이지]|토큰,토큰\n토큰: 이름 일부 · =정확한 이름 · pkg:패키지(com.google.* 가능) · " +
            "re:정규식(쉼표 없이) · dev:개발자 · cat:카테고리 · *\n" +
            "폴더이름#빨강 / #FF5722 로 폴더 색 · (폴더밖) 꺼내기 · (제외) 건드리지 않기\n" +
            "!폴더안정렬 · !앱정렬 · !폴더정렬 · !배치 · !최소앱 · !폴더색 아이콘|카테고리|기본",
        model.prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!,
        { Classifier.validate(it) },
        { model.saveRules(it) },
        onClose,
    )
}

@Composable
fun FolderNamesSheet(model: AppModel, onClose: () -> Unit) {
    TextEditSheet(
        "폴더 이름",
        "한 줄에 하나 · 기본이름=내이름\n기본 이름: " + SmartClassifier.defaultFolders().joinToString(", "),
        model.prefs.getString(Config.KEY_FOLDER_NAMES, "")!!,
        { text ->
            text.lines().withIndex().firstOrNull { (_, l) ->
                val t = l.trim()
                t.isNotEmpty() && !t.startsWith("#") && (t.indexOf('=') <= 0 || t.endsWith("="))
            }?.let { "${it.index + 1}번째 줄: '기본이름=내이름' 형식이 아닙니다" }
        },
        { model.edit({ putString(Config.KEY_FOLDER_NAMES, it) }) },
        onClose,
    )
}

// ---- folder rules (form) -----------------------------------------------------------------------

/** One rule line in editable form. kind: 0 = folder, 1 = take out of folders, 2 = leave alone. */
class RuleItem(
    var folder: String = "", var kind: Int = 0, var page: Int = 0, var all: Boolean = false, var color: String = "",
    val categories: MutableList<String> = mutableListOf(), val packages: MutableList<String> = mutableListOf(),
    val words: MutableList<String> = mutableListOf(),
    /** Exact apps (re:^pkg$, written when apps are moved by hand), developers, other tokens. */
    val pins: MutableList<String> = mutableListOf(), val devs: MutableList<String> = mutableListOf(),
    val raw: MutableList<String> = mutableListOf(),
) {
    fun toLine(): String {
        val tokens = categories.map { "cat:$it" } + packages.map { "pkg:$it" } +
            pins.map { "re:^" + it.replace(".", "\\.") + "$" } + devs.map { "dev:$it" } + raw + words +
            (if (all) listOf("*") else emptyList())
        val head = when (kind) {
            1 -> Classifier.LOOSE + (if (page > 0) "@$page" else "")
            2 -> Classifier.EXCLUDE_ALIAS
            else -> folder.trim() + (if (color.isNotBlank()) "#$color" else "") + (if (page > 0) "@$page" else "")
        }
        return head + "|" + tokens.joinToString(",")
    }

    val valid get() = (kind != 0 || folder.isNotBlank()) &&
        (all || categories.isNotEmpty() || packages.isNotEmpty() || words.isNotEmpty() || pins.isNotEmpty() || devs.isNotEmpty() || raw.isNotEmpty())

    fun copy() = RuleItem(folder, kind, page, all, color, categories.toMutableList(), packages.toMutableList(), words.toMutableList(),
        pins.toMutableList(), devs.toMutableList(), raw.toMutableList())

    companion object {
        fun parse(line: String): RuleItem? {
            val bar = line.indexOf('|')
            if (bar <= 0) return null
            val it = RuleItem()
            var head = line.substring(0, bar).trim()
            repeat(2) { _ ->
                val at = head.lastIndexOf('@')
                val hash = head.lastIndexOf('#')
                if (at > 0 && at > hash && head.length > at + 1 && head.substring(at + 1).trim().all { c -> c.isDigit() }) {
                    it.page = head.substring(at + 1).trim().toInt()
                    head = head.substring(0, at).trim()
                } else if (hash > 0 && hash > at) {
                    it.color = head.substring(hash + 1).trim()
                    head = head.substring(0, hash).trim()
                }
            }
            when (head) {
                Classifier.LOOSE -> it.kind = 1
                Classifier.EXCLUDE, Classifier.EXCLUDE_ALIAS -> it.kind = 2
                else -> it.folder = head
            }
            for (raw in line.substring(bar + 1).split(",")) {
                val t = raw.trim()
                when {
                    t.isEmpty() -> Unit
                    t == "*" -> it.all = true
                    t.lowercase().startsWith("cat:") -> it.categories.add(t.substring(4).trim())
                    t.lowercase().startsWith("pkg:") && !t.contains("*") -> it.packages.add(t.substring(4).trim())
                    t.matches(Regex("(?i)re:\\^[A-Za-z0-9_\\\\.]+\\$")) -> it.pins.add(t.substring(4, t.length - 1).replace("\\.", "."))
                    t.lowercase().startsWith("dev:") -> it.devs.add(t.substring(4).trim())
                    t.lowercase().startsWith("re:") || t.lowercase().startsWith("pkg:") || t.startsWith("=") -> it.raw.add(t)
                    else -> it.words.add(t)
                }
            }
            return it
        }
    }
}

/** A rule's warning: [label] on the row, [text] when tapped; [severe] = it loses apps to another rule. */
class RuleWarning(val label: String, val text: String, val severe: Boolean)

/**
 * Checks every rule against the installed apps: the topmost matching rule wins, so a lower
 * rule that matches the same apps is (partly) hidden by it. Rules that share a condition with
 * one above but match nothing installed yet "may" conflict.
 */
fun ruleWarnings(context: Context, rulesText: String, apps: List<Pair<String, String>>): List<RuleWarning?> {
    val c = Classifier(rulesText)
    val n = c.size()
    if (n == 0) return emptyList()
    val pkgs = apps.map { it.first }.toTypedArray()
    val labels = apps.map { it.second }.toTypedArray()
    val auto = runCatching {
        ClassifierProvider.classify(context, pkgs, labels, false, "detail").getStringArray("targets")
    }.getOrNull()
    val pm = context.packageManager
    val total = IntArray(n)
    val lostTo = Array(n) { IntArray(n) }
    for (i in pkgs.indices) {
        val pk = pkgs[i]
        val hits = c.matchingRules(pk, labels[i], auto?.getOrNull(i)) { com.auto.classify.core.Developers.of(pm, pk) }
        if (hits.isEmpty()) continue
        val winner = hits[0]
        total[winner]++
        for (k in hits.drop(1)) {
            total[k]++
            lostTo[k][winner]++
        }
    }
    return (0 until n).map { k ->
        val lost = lostTo[k].sum()
        val top = lostTo[k].indices.maxByOrNull { lostTo[k][it] } ?: -1
        when {
            total[k] > 0 && lost == total[k] -> RuleWarning("적용 안 됨", "${top + 1}번 규칙이 먼저 가져가요", true)
            lost > 0 -> RuleWarning("겹침 $lost", "앱 ${lost}개는 ${top + 1}번 규칙을 따라요", true)
            total[k] == 0 -> RuleWarning("해당 앱 없음", "지금 설치된 앱 중 맞는 게 없어요", false)
            else -> null
        }
    }
}

@Composable
fun RulesSheet(model: AppModel, onText: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var reload by remember { mutableStateOf(0) }
    var showSets by remember { mutableStateOf(false) }
    val raw = remember(reload) { model.prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!! }
    val others = remember(reload) { raw.lines().map { it.trim() }.filter { it.startsWith("#") || it.startsWith("!") } }
    val items = remember(reload) { mutableStateListOf<RuleItem>().apply { raw.lines().mapNotNullTo(this) { l -> RuleItem.parse(l.trim()).takeIf { !l.trim().startsWith("#") && !l.trim().startsWith("!") } } } }
    var editing by remember { mutableStateOf<Pair<Int, RuleItem>?>(null) }
    var dragIndex by remember { mutableStateOf(-1) }
    var dragOffset by remember { mutableStateOf(0f) }
    val heights = remember { mutableStateMapOf<RuleItem, Int>() }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps(context) } }
    val labels = apps.toMap()
    val rulesText = items.joinToString("\n") { it.toLine() }
    var warnings by remember { mutableStateOf<List<RuleWarning?>>(emptyList()) }
    LaunchedEffect(rulesText, apps) {
        if (apps.isNotEmpty() && dragIndex < 0) warnings = withContext(Dispatchers.IO) { ruleWarnings(context, rulesText, apps) }
    }

    fun save() {
        model.saveRules((others + items.map { it.toLine() }).joinToString("\n"))
    }

    WindowBottomSheet(show = true, title = "폴더 규칙", onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 660.dp).verticalScroll(rememberScrollState())) {
            if (items.isEmpty()) Hint("규칙 없음")
            items.forEachIndexed { i, it ->
              key(it) {
                val title = ruleSubject(it, labels)
                val summary = when (it.kind) {
                    1 -> "→ 폴더 밖으로"
                    2 -> "→ 그대로 두기"
                    else -> "→ " + it.folder + " 폴더"
                } + (if (it.page > 0) " · ${it.page}페이지" else "") + (if (it.color.isNotBlank()) " · ${it.color}" else "")
                val dragging = dragIndex == i
                Row(
                    Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffset else 0f; shadowElevation = if (dragging) 12f else 0f }
                        .background(if (dragging) MiuixTheme.colorScheme.surfaceContainerHigh else androidx.compose.ui.graphics.Color.Transparent,
                            RoundedCornerShape(16.dp))
                        .onSizeChanged { s -> heights[it] = s.height },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        ArrowPreference(
                            title = "${i + 1}. $title",
                            summary = summary,
                            onClick = { editing = items.indexOf(it) to it.copy() },
                        )
                    }
                    warnings.getOrNull(i)?.takeIf { _ -> dragIndex < 0 }?.let { w ->
                        val tint = if (w.severe) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary
                        Text(
                            w.label,
                            Modifier.clip(RoundedCornerShape(50)).background(tint.copy(alpha = 0.12f))
                                .clickable { model.toast(w.text) }.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MiuixTheme.textStyles.footnote1, color = tint, maxLines = 1,
                        )
                    }
                    // ≡ handle: drag up/down to change the order.
                    Text(
                        "≡",
                        Modifier.size(48.dp).wrapContentSize().pointerInput(it) {
                            detectVerticalDragGestures(
                                onDragStart = { _ -> dragIndex = items.indexOf(it); dragOffset = 0f },
                                onVerticalDrag = { ch, dy ->
                                    ch.consume()
                                    dragOffset += dy
                                    val idx = dragIndex
                                    if (idx < 0) return@detectVerticalDragGestures
                                    val below = items.getOrNull(idx + 1)
                                    val above = items.getOrNull(idx - 1)
                                    if (below != null && dragOffset > (heights[below] ?: 150) / 2f) {
                                        dragOffset -= heights[below] ?: 150
                                        items.add(idx + 1, items.removeAt(idx))
                                        dragIndex = idx + 1
                                    } else if (above != null && dragOffset < -(heights[above] ?: 150) / 2f) {
                                        dragOffset += heights[above] ?: 150
                                        items.add(idx - 1, items.removeAt(idx))
                                        dragIndex = idx - 1
                                    }
                                },
                                onDragEnd = { dragIndex = -1; dragOffset = 0f; save() },
                                onDragCancel = { dragIndex = -1; dragOffset = 0f; save() },
                            )
                        },
                        fontSize = 22.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
              }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton("템플릿 · 세트", { showSets = true }, Modifier.weight(1f))
                TextButton("텍스트로 편집", onText, Modifier.weight(1f))
            }
            SheetActions("닫기", onClose, "규칙 추가", { editing = -1 to RuleItem() })
        }
    }
    if (showSets) RuleSetsSheet(model, onApplied = { reload++ }) { showSets = false }

    editing?.let { (index, item) ->
        RuleDialog(model, item, apps, isNew = index < 0, onDelete = {
            items.removeAt(index)
            save()
            editing = null
        }, onSave = {
            if (index < 0) items.add(it) else items[index] = it
            save()
            editing = null
        }, onClose = { editing = null })
    }
}

/** Ready-made rule sets: name -> rule lines (sort settings are kept when one is applied). */
private val TEMPLATES = listOf(
    "비우기 (자동 분류만)" to "",
    "삼성 · 구글 따로" to "삼성|dev:samsung\n구글|dev:google",
    "게임 따로" to "게임|cat:게임",
    "AI 앱 모으기" to "AI|cat:AI",
    "생활 묶기 (금융·쇼핑·음식)" to "생활|cat:금융,cat:쇼핑,cat:음식",
    "미디어 묶기 (음악·동영상·사진)" to "미디어|cat:음악,cat:동영상,cat:사진·디자인",
    "루팅 앱은 폴더 밖으로" to "(폴더밖)|cat:루팅·모듈",
)

private val MODE_LABELS = listOf(
    Config.MODE_CATEGORY to "카테고리", Config.MODE_COLOR to "색상", Config.MODE_AI to "AI", Config.MODE_RULES to "규칙만",
)

private fun ruleSets(model: AppModel): Map<String, String> = runCatching {
    val o = org.json.JSONObject(model.prefs.getString(KEY_RULE_SETS, "{}")!!)
    o.keys().asSequence().associateWith { o.getString(it) }
}.getOrDefault(emptyMap())

private const val KEY_RULE_SETS = "rule_sets"

/** Replace all rules with a template or a saved set; save the current rules as a set. */
@Composable
private fun RuleSetsSheet(model: AppModel, onApplied: () -> Unit, onClose: () -> Unit) {
    var sets by remember { mutableStateOf(ruleSets(model)) }
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) }
    var newName by remember { mutableStateOf("") }
    val current = model.prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!
    fun saveSets(m: Map<String, String>) {
        sets = m
        model.edit({ putString(KEY_RULE_SETS, org.json.JSONObject(m).toString()) }, reload = false)
    }

    WindowBottomSheet(show = true, title = "템플릿 · 세트", onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState())) {
            SmallTitle("템플릿", insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp, 4.dp))
            TEMPLATES.forEach { (name, text) ->
                ArrowPreference(name, onClick = { confirm = name to text })
            }
            SmallTitle("다른 방식에서 가져오기", insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp, 12.dp, 0.dp, 4.dp))
            val activeMode = model.prefs.getString(Config.KEY_MODE, Config.MODE_CATEGORY)
            MODE_LABELS.filter { it.first != activeMode }.forEach { (m, label) ->
                val text = model.rulesOf(m)
                val n = text.lines().count { it.contains('|') }
                ArrowPreference(label, summary = if (n == 0) "규칙 없음" else "${n}개 추가하기", enabled = n > 0, onClick = {
                    val now = model.prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!
                    model.edit({ putString(Config.KEY_RULES, mergeRules(text, now)) })
                    model.toast("$label 규칙 ${n}개를 가져왔어요")
                    onApplied()
                    onClose()
                })
            }
            SmallTitle("내 세트", insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp, 12.dp, 0.dp, 4.dp))
            if (sets.isEmpty()) Hint("없음")
            sets.forEach { (name, text) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        ArrowPreference(name, summary = "${text.lines().count { it.contains('|') && !it.trim().startsWith("#") }}개",
                            onClick = { confirm = name to text })
                    }
                    TextButton("삭제", { saveSets(sets - name) }, minHeight = 36.dp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(value = newName, onValueChange = { newName = it }, useLabelAsPlaceholder = true, label = "지금 규칙을 세트로 저장",
                    singleLine = true, modifier = Modifier.weight(1f))
                TextButton("저장", {
                    saveSets(sets + (newName.trim() to current))
                    newName = ""
                }, Modifier.padding(start = 8.dp), enabled = newName.isNotBlank(), colors = ButtonDefaults.textButtonColorsPrimary())
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    confirm?.let { (name, text) ->
        WindowDialog(show = true, title = "'$name'(으)로 바꿀까요?", summary = "지금 규칙은 사라져요", onDismissRequest = { confirm = null }) {
            SheetActions("취소", { confirm = null }, "바꾸기", {
                val isTemplate = TEMPLATES.any { it.first == name }
                // Templates swap only the rules and keep the sort settings; a saved set is restored whole.
                val next = if (isTemplate) {
                    (text.lines() + current.lines().filter { it.trim().startsWith("!") }).filter { it.isNotBlank() }.joinToString("\n")
                } else text
                model.saveRules(next)
                confirm = null
                onApplied()
                onClose()
            })
        }
    }
}

/** Quick developer choices in the rule editor: label -> dev: token. */
private val QUICK_DEVS = listOf("삼성 앱" to "samsung", "구글 앱" to "google")

/** Plain sentence for a rule's apps: "삼성 앱 · 게임 카테고리 · 카카오톡 외 2개". */
fun ruleSubject(it: RuleItem, labels: Map<String, String>): String {
    val parts = buildList {
        if (it.all) add("나머지 모든 앱")
        it.devs.forEach { d -> add(QUICK_DEVS.firstOrNull { q -> q.second.equals(d, true) }?.first ?: "개발자 $d") }
        if (it.categories.isNotEmpty()) add(it.categories.joinToString(", ") + " 카테고리")
        val picked = (it.pins + it.packages).map { p -> labels[p] ?: p }
        if (picked.isNotEmpty()) add(picked.take(3).joinToString(", ") + if (picked.size > 3) " 외 ${picked.size - 3}개" else "")
        if (it.words.isNotEmpty()) add("이름에 " + it.words.joinToString(", "))
        if (it.raw.isNotEmpty()) add("패턴 " + it.raw.joinToString(", "))
    }
    return parts.joinToString(" · ").ifEmpty { "대상 없음" }
}

@Composable
private fun RuleDialog(
    model: AppModel, start: RuleItem, apps: List<Pair<String, String>>, isNew: Boolean,
    onDelete: () -> Unit, onSave: (RuleItem) -> Unit, onClose: () -> Unit,
) {
    var name by remember { mutableStateOf(start.folder) }
    var kind by remember { mutableStateOf(start.kind) }
    var page by remember { mutableStateOf(start.page) }
    var color by remember { mutableStateOf(start.color) }
    var all by remember { mutableStateOf(start.all) }
    val cats = remember { mutableStateListOf<String>().apply { addAll(start.categories) } }
    // Apps picked by hand are stored as exact picks (they win over broad rules).
    val picked = remember { mutableStateListOf<String>().apply { addAll(start.pins + start.packages) } }
    val devs = remember { mutableStateListOf<String>().apply { addAll(start.devs.filter { d -> QUICK_DEVS.any { it.second.equals(d, true) } }) } }
    val advancedInit = start.words + start.raw + start.devs.filter { d -> QUICK_DEVS.none { it.second.equals(d, true) } }.map { "dev:$it" }
    var advanced by remember { mutableStateOf(advancedInit.joinToString(", ")) }
    var showAdvanced by remember { mutableStateOf(advancedInit.isNotEmpty()) }
    var showMore by remember { mutableStateOf(start.page > 0 || start.color.isNotBlank()) }
    var pickApps by remember { mutableStateOf(false) }
    var pickCats by remember { mutableStateOf(false) }
    val labels = remember(apps) { apps.toMap() }

    WindowDialog(show = true, title = if (isNew) "규칙 추가" else "규칙 편집", onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 580.dp).verticalScroll(rememberScrollState())) {
            Text("어떤 앱을?", Modifier.padding(vertical = 4.dp), style = MiuixTheme.textStyles.title4)
            ArrowPreference("앱 고르기", summary = if (picked.isEmpty()) "고른 앱 없음"
                else picked.take(3).joinToString(", ") { labels[it] ?: it } + if (picked.size > 3) " 외 ${picked.size - 3}개" else "",
                onClick = { pickApps = true })
            ArrowPreference("카테고리로 고르기", summary = if (cats.isEmpty()) "없음" else cats.joinToString(", "),
                onClick = { pickCats = true })
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QUICK_DEVS.forEach { (label, token) ->
                    val on = devs.any { it.equals(token, true) }
                    TextButton(if (on) "✓ $label" else label, {
                        if (on) devs.removeAll { it.equals(token, true) } else devs.add(token)
                    }, Modifier.weight(1f), minHeight = 40.dp,
                        colors = if (on) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
                }
            }
            SwitchPreference(checked = all, onCheckedChange = { all = it }, title = "나머지 모든 앱")
            Text(
                if (showAdvanced) "고급 조건 접기" else "고급 조건 (이름 단어 · 패키지 · 개발자)",
                Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }.padding(vertical = 10.dp),
                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary,
            )
            if (showAdvanced) {
                TextField(value = advanced, onValueChange = { advanced = it },
                    useLabelAsPlaceholder = true, label = "예: 은행, pkg:com.kakao.*, dev:naver",
                    singleLine = true, modifier = Modifier.fillMaxWidth())
            }

            Text("어디로?", Modifier.padding(top = 12.dp, bottom = 6.dp), style = MiuixTheme.textStyles.title4)
            SegTabs(listOf("폴더에 넣기", "폴더 밖으로", "그대로 두기"), kind, { kind = it })
            Spacer(Modifier.height(8.dp))
            if (kind == 0) {
                TextField(value = name, onValueChange = { name = it }, useLabelAsPlaceholder = true, label = "폴더 이름", singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                val suggestions = (model.folders + categoriesFor(model)).distinct()
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                    suggestions.forEach { s ->
                        TextButton(s, { name = s }, Modifier.padding(end = 6.dp), minHeight = 32.dp)
                    }
                }
            }
            if (kind != 2) {
                Text(
                    if (showMore) "페이지·색 접기" else "페이지·폴더 색 정하기",
                    Modifier.fillMaxWidth().clickable { showMore = !showMore }.padding(vertical = 10.dp),
                    style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary,
                )
            }
            if (showMore && kind != 2) {
                WindowDropdownPreference(
                    items = listOf("자동") + (1..10).map { "${it}페이지" },
                    selectedIndex = page.coerceIn(0, 10),
                    title = "페이지",
                    onSelectedIndexChange = { page = it },
                )
                if (kind == 0) {
                    val colorItems = listOf("지정 안 함", "기본") + com.auto.classify.core.FolderColors.names().toList()
                    val sel = colorItems.indexOf(color).let { if (it < 0) 0 else it }
                    WindowDropdownPreference(
                        items = colorItems,
                        selectedIndex = sel,
                        title = "폴더 색",
                        summary = if (color.isNotBlank() && sel == 0) "직접 지정: #$color" else null,
                        onSelectedIndexChange = { color = if (it == 0) "" else colorItems[it] },
                    )
                }
            }

            val extra = if (showAdvanced) advanced.split(",").map { it.trim() }.filter { it.isNotEmpty() } else advancedInit
            val extraDevs = extra.filter { it.lowercase().startsWith("dev:") }.map { it.substring(4).trim() }
            val result = RuleItem(name, kind, if (kind == 2) 0 else page, all, if (kind == 0) color else "",
                cats.toMutableList(), mutableListOf(),
                extra.filterNot { it.contains(':') || it.startsWith("=") }.toMutableList(),
                picked.toMutableList(),
                (devs + extraDevs).distinct().toMutableList(),
                extra.filter { (it.contains(':') && !it.lowercase().startsWith("dev:")) || it.startsWith("=") }.toMutableList())
            if (!result.valid) ErrorText(if (kind == 0 && name.isBlank()) "폴더 이름을 입력하세요" else "앱을 하나 이상 고르세요")
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (!isNew) TextButton("삭제", onDelete, Modifier.weight(1f))
                TextButton("취소", onClose, Modifier.weight(1f))
                TextButton("저장", { if (result.valid) onSave(result) }, Modifier.weight(1f), enabled = result.valid,
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }

    if (pickApps) {
        var query by remember { mutableStateOf("") }
        WindowBottomSheet(show = true, title = "앱 고르기", onDismissRequest = { pickApps = false }) {
            Column(Modifier.heightIn(max = 640.dp)) {
                TextField(value = query, onValueChange = { query = it }, useLabelAsPlaceholder = true, label = "검색",
                    singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    // Picked apps first, so the choice is easy to review.
                    val q = query.trim().lowercase()
                    apps.filter { (pkg, label) -> q.isEmpty() || label.lowercase().contains(q) || pkg.contains(q) }
                        .sortedBy { (pkg, _) -> if (pkg in picked) 0 else 1 }
                        .forEach { (pkg, label) ->
                            CheckboxPreference(title = label, checked = pkg in picked,
                                onCheckedChange = { v -> if (v) picked.add(pkg) else picked.remove(pkg) },
                                startAction = { AppIcon(pkg, Modifier.size(36.dp)) })
                        }
                }
                TextButton("완료 (${picked.size}개)", { pickApps = false }, Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
    if (pickCats) {
        WindowBottomSheet(show = true, title = "카테고리", onDismissRequest = { pickCats = false }) {
            Column(Modifier.heightIn(max = 640.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    categoriesFor(model).forEach { c ->
                        CheckboxPreference(title = c, checked = c in cats, onCheckedChange = { v -> if (v) cats.add(c) else cats.remove(c) })
                    }
                }
                TextButton("완료", { pickCats = false }, Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
    }
}

/**
 * One past AI answer: when and what was asked on top, the answer folded to a few lines until tapped,
 * so a long history stays scannable.
 */
@Composable
private fun AiHistoryEntry(time: String, request: String, text: String) {
    var expanded by remember { mutableStateOf(false) }
    val long = text.length > 160 || text.count { it == '\n' } > 3
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp)
            .then(if (long) Modifier.clickable { expanded = !expanded } else Modifier),
    ) {
        Text(
            time + (if (request.isBlank()) "" else " · " + request.replace('\n', ' ')),
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text,
            Modifier.fillMaxWidth().padding(top = 2.dp),
            style = MiuixTheme.textStyles.body2,
            maxLines = if (expanded || !long) Int.MAX_VALUE else 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (long) {
            Text(if (expanded) "접기" else "펼치기", style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The AI's latest reply or summary, and the earlier ones on demand. */
/**
 * An answer or warning list. Bullet lines ("• ") beyond the first few fold away behind one tap, so a
 * long list of mismatched items doesn't push the rest of the screen off.
 */
@Composable
fun CollapsibleAnswer(text: String) {
    var open by remember(text) { mutableStateOf(false) }
    val lines = text.lines()
    val bullets = lines.count { it.startsWith("• ") }
    val keep = 4
    if (bullets <= keep) {
        Text(text, Modifier.fillMaxWidth().padding(top = 8.dp), style = MiuixTheme.textStyles.body2)
        return
    }
    var seen = 0
    val shown = if (open) lines else lines.filter { l ->
        if (l.startsWith("• ")) { seen++; seen <= keep } else true
    }
    Text(shown.joinToString("\n"), Modifier.fillMaxWidth().padding(top = 8.dp), style = MiuixTheme.textStyles.body2)
    Text(
        if (open) "접기" else "외 ${bullets - keep}개 더 보기",
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.body2,
    )
}

@Composable
fun AiReply(model: AppModel) {
    if (model.aiRunning) return
    var open by remember { mutableStateOf(false) }
    model.aiAnswer?.let { CollapsibleAnswer(it) }
    val past = model.aiHistory
    if (past.isEmpty()) return
    Text(
        if (open) "이전 결과 접기" else "이전 결과 ${past.size}개 보기",
        Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp),
        color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.body2,
    )
    if (open) {
        val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA) }
        for (e in past) {
            AiHistoryEntry(fmt.format(java.util.Date(e.time)), e.request, e.text)
        }
        Text("기록 지우기", Modifier.fillMaxWidth().clickable { model.clearAiHistory() }.padding(vertical = 8.dp),
            color = MiuixTheme.colorScheme.error, style = MiuixTheme.textStyles.body2)
    }
}

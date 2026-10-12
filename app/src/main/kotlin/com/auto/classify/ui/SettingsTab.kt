package com.auto.classify.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.auto.classify.ai.AiUsage
import com.auto.classify.ai.ApiLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val OPENAI_PRESETS = listOf(
    Triple("OpenRouter", "https://openrouter.ai/api/v1", "openrouter/free"),
    Triple("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    Triple("직접 입력", "", ""),
)

@Composable
fun SettingsTab(model: AppModel, activity: MainActivity) {
    LazyColumn(Modifier.fillMaxSize().overScrollVertical().scrollEndHaptic(), contentPadding = PaddingValues(bottom = 12.dp)) {
        item { SmallTitle("화면") }
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                WindowDropdownPreference(
                    items = ThemeChoice.names,
                    selectedIndex = ThemeChoice.values.indexOf(ThemeChoice.mode).coerceAtLeast(0),
                    title = "테마",
                    onSelectedIndexChange = {
                        ThemeChoice.mode = ThemeChoice.values[it]
                        model.edit({ putString(ThemeChoice.KEY, ThemeChoice.values[it]) }, reload = false)
                    },
                )
            }
        }
        item { SmallTitle("AI 연결") }
        item { Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { AiConnection(model) } }
        item { SmallTitle("AI 사용 기록") }
        item { Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { AiUsageCard() } }
        item { SmallTitle("백업 및 불러오기") }
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference("설정 내보내기", summary = "규칙·AI 선택·옵션을 파일로 저장 (API 키 제외)", onClick = { activity.exportSettings() })
                ArrowPreference("설정 불러오기", summary = "내보낸 파일에서 되돌리기", onClick = { activity.importSettings() })
            }
        }
        item { SmallTitle("업데이트") }
        item { Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { UpdateCard() } }
        item { SmallTitle("문제 해결") }
        item {
            Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                ArrowPreference("진단 보기", onClick = {
                    model.requestHealth()
                    model.showDiagnosis = true
                })
                ArrowPreference("로그 내보내기", onClick = { activity.exportLogs() })
            }
        }
    }
}

@Composable
private fun AiConnection(model: AppModel) {
    val context = LocalContext.current
    val sp = remember { ApiLlm.prefs(context) }
    var provider by remember { mutableStateOf(sp.getString(ApiLlm.KEY_PROVIDER, ApiLlm.GEMINI)!!) }
    var modelName by remember {
        mutableStateOf(sp.getString(ApiLlm.KEY_MODEL, "")!!.ifBlank { if (provider == ApiLlm.GEMINI) ApiLlm.DEFAULT_GEMINI else "" })
    }
    var base by remember { mutableStateOf(sp.getString(ApiLlm.KEY_BASE, OPENAI_PRESETS[0].second)!!) }
    /** Which service the key belongs to: Gemini, a preset's address, or a custom one. */
    fun slot(): String = when {
        provider == ApiLlm.GEMINI -> "gemini"
        OPENAI_PRESETS.dropLast(1).any { it.second == base } -> base
        else -> "custom"
    }
    var key by remember { mutableStateOf(ApiLlm.keyFor(sp, sp.getString(ApiLlm.KEY_SLOT, slot())!!)) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var notes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun save() {
        sp.edit().putString(ApiLlm.KEY_PROVIDER, provider).putString(ApiLlm.KEY_SLOT, slot())
            .putString(ApiLlm.KEY_PREFIX + slot(), key.trim()).remove(ApiLlm.KEY_KEY)
            .putString(ApiLlm.KEY_MODEL, modelName.trim()).putString(ApiLlm.KEY_BASE, base.trim()).apply()
        model.edit({}, reload = false) // re-read "AI connected" elsewhere
    }

    /** Asks the service which models this key can use; fixes a missing or retired model name. */
    fun loadModels() {
        if (key.isBlank()) return
        loading = true
        message = null
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val api = ApiLlm(provider, key, modelName, base, null)
                    api.listModels().also { notes = HashMap(api.notes) }
                }
            }
            loading = false
            r.onSuccess { list ->
                models = list
                if (modelName !in list && !(provider == ApiLlm.GEMINI && modelName == ApiLlm.DEFAULT_GEMINI)) {
                    val best = if (provider == ApiLlm.GEMINI) ApiLlm.bestModel(list) else list.firstOrNull()
                    if (best != null) {
                        if (modelName.isNotBlank()) message = "'$modelName' 모델은 없어서 $best 로 바꿨어요"
                        modelName = best
                        save()
                    }
                }
                if (message == null) message = "연결됨 · 모델 ${list.size}개"
            }.onFailure { message = "연결 실패: ${it.message}" }
        }
    }

    LaunchedEffect(Unit) { if (key.isNotBlank()) loadModels() }

    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        SegTabs(listOf("Google AI Studio", "OpenAI 호환"), if (provider == ApiLlm.GEMINI) 0 else 1, {
            save() // the current service keeps its own key
            provider = if (it == 0) ApiLlm.GEMINI else ApiLlm.OPENAI
            modelName = if (provider == ApiLlm.GEMINI) ApiLlm.DEFAULT_GEMINI else ""
            models = emptyList()
            key = ApiLlm.keyFor(sp, slot())
            save()
            loadModels()
        })
        if (provider == ApiLlm.GEMINI) {
            Text(
                "aistudio.google.com 에서 무료 키 만들기 →",
                Modifier.fillMaxWidth().clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                }.padding(vertical = 10.dp),
                color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.body2,
            )
        }
    }
    if (provider != ApiLlm.GEMINI) {
        val idx = OPENAI_PRESETS.indexOfFirst { it.second == base }.let { if (it < 0) OPENAI_PRESETS.size - 1 else it }
        WindowDropdownPreference(
            items = OPENAI_PRESETS.map { it.first },
            selectedIndex = idx,
            title = "서비스",
            summary = base.ifBlank { "주소를 입력하세요" },
            onSelectedIndexChange = {
                val pr = OPENAI_PRESETS[it]
                save()
                base = pr.second
                modelName = pr.third
                key = ApiLlm.keyFor(sp, slot())
                save()
                loadModels()
            },
        )
        if (idx == OPENAI_PRESETS.size - 1) {
            TextField(value = base, onValueChange = { base = it; save() }, useLabelAsPlaceholder = true,
                label = "주소 (…/v1)", singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        }
    }
    TextField(
        value = key, onValueChange = { key = it; save() }, useLabelAsPlaceholder = true, label = "API 키",
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    if (models.isNotEmpty()) {
        WindowDropdownPreference(
            items = models.map { m -> notes[m]?.let { "$m · $it" } ?: m },
            selectedIndex = models.indexOf(modelName).coerceAtLeast(0),
            title = "모델",
            
            onSelectedIndexChange = { modelName = models[it]; save() },
        )
    } else {
        ArrowPreference("모델", summary = modelName.ifBlank { "키를 넣으면 목록을 불러와요" }, onClick = { loadModels() })
    }
    if (ApiLlm.supportsWeb(provider, base)) {
        var web by remember { mutableStateOf(sp.getBoolean(ApiLlm.KEY_WEB, false)) }
        SwitchPreference(
            title = "웹 검색 허용",
            summary = "모르는 앱을 검색해서 분류해요 (요청이 느려지고 토큰·요금이 더 들 수 있어요)",
            checked = web,
            onCheckedChange = { web = it; sp.edit().putBoolean(ApiLlm.KEY_WEB, it).apply() },
        )
    } else {
        Text("이 서비스는 웹 검색을 지원하지 않아요 (Gemini, OpenRouter만)",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    var tools by remember { mutableStateOf(sp.getBoolean(ApiLlm.KEY_TOOLS, true)) }
    SwitchPreference(
        title = "AI 도구 사용",
        summary = "AI가 현재 구성·폴더 내용·앱 사용량을 직접 확인하며 판단해요 (요청이 느려지고 토큰이 더 들어요)",
        checked = tools,
        onCheckedChange = { tools = it; sp.edit().putBoolean(ApiLlm.KEY_TOOLS, it).apply() },
    )
    var granted by remember { mutableStateOf(AiTools.usageGranted(context)) }
    val owner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = AiTools.usageGranted(context)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    ArrowPreference(
        title = "앱 사용 기록 접근",
        summary = if (granted) "허용됨 · AI가 앱 사용량을 볼 수 있어요" else "허용 안 됨 · 눌러서 설정을 열고 AutoClassify를 켜 주세요",
        onClick = {
            context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .setData(Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
    )
    TextButton(
        if (loading) "확인하는 중…" else "연결 확인",
        { loadModels() },
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        enabled = !loading && key.isNotBlank(),
    )
    message?.let {
        Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MiuixTheme.textStyles.body2,
            color = if (it.startsWith("연결 실패")) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
    Text("키는 이 기기에만 저장돼요",
        Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp), style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun AiUsageCard() {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    val log = remember(version) { AiUsage.log(context) }
    val today = remember(version) { AiUsage.today(context) }
    val fmt = remember { SimpleDateFormat("M/d HH:mm", Locale.KOREAN) }
    ArrowPreference(
        "오늘 ${today[0]}번 · 토큰 ${"%,d".format(today[1])}",
        summary = if (log.length() == 0) "아직 요청 없음" else if (expanded) "접기" else "최근 기록 보기 (전체 ${log.length()}개)",
        onClick = { expanded = !expanded; version++ },
    )
    if (expanded) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)) {
            for (i in log.length() - 1 downTo maxOf(0, log.length() - 30)) {
                val o = log.optJSONObject(i) ?: continue
                val tokens = o.optInt("in") + o.optInt("out")
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(fmt.format(Date(o.optLong("t"))), Modifier.padding(end = 8.dp), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Text(
                        "[${o.optString("kind")}] ${o.optString("detail")}" + if (tokens > 0) " · ${"%,d".format(tokens)}토큰" else "",
                        style = MiuixTheme.textStyles.footnote1,
                        color = if (o.optBoolean("ok")) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.error,
                    )
                }
            }
            TextButton("기록 지우기", { AiUsage.clear(context); version++ }, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val current = remember { Updater.current(context) }
    val latest = Updater.latest
    val update = latest != null && Updater.newer(latest.version, current)
    Column(Modifier.padding(16.dp)) {
        Text(if (update) "새 버전 ${latest!!.version} 있음" else "현재 버전 $current", style = MiuixTheme.textStyles.title4)
        if (update && latest!!.notes.isNotBlank()) {
            Text(latest.notes.take(600), Modifier.padding(top = 6.dp), style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        Updater.progress?.let { pr ->
            LinearProgressIndicator(progress = pr, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            Text("받는 중 ${(pr * 100).toInt()}%", Modifier.padding(top = 4.dp), style = MiuixTheme.textStyles.footnote1)
        }
        Updater.status?.let { Text(it, Modifier.padding(top = 8.dp), style = MiuixTheme.textStyles.body2) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            if (update) {
                TextButton("다운로드 후 설치", { Updater.install(context) }, Modifier.weight(1f),
                    enabled = Updater.progress == null, colors = ButtonDefaults.textButtonColorsPrimary())
            } else {
                TextButton(if (Updater.checking) "확인 중…" else "업데이트 확인", { Updater.check(context) }, Modifier.weight(1f),
                    enabled = !Updater.checking)
            }
        }
        Text(
            "릴리즈 페이지 열기",
            Modifier.fillMaxWidth().clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.pageUrl))) }
                .padding(top = 12.dp),
            color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.body2,
        )
    }
}

/** Shown at the top of the organize tab when a newer release exists. */
@Composable
fun UpdateBanner(openSettings: () -> Unit) {
    val context = LocalContext.current
    val latest = Updater.latest ?: return
    if (!Updater.newer(latest.version, Updater.current(context))) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp),
        insideMargin = PaddingValues(16.dp),
        onClick = openSettings,
    ) {
        Text("새 버전 ${latest.version} 이 나왔어요", style = MiuixTheme.textStyles.title4)
            }
}

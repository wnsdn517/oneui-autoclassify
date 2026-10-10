package com.auto.classify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.icon.extended.Send
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.auto.classify.ai.ApiLlm
import com.auto.classify.core.Config
import com.auto.classify.core.Directives
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

val COLOR_METHODS = listOf("CENTROID", "AVERAGE", "MODE", "POSITION", "SPREAD", "SIMILARITY")
val COLOR_METHOD_NAMES = listOf("중심점", "평균", "최빈값", "위치", "표준편차", "유사도")
val COLOR_METHOD_HELP = listOf(
    "주요 색 군집의 중심 (기본)", "아이콘 전체 색의 평균", "가장 많이 쓰인 색",
    "아이콘의 지정한 지점 색", "색이 얼마나 다양한지: 단색 · 투톤 · 다채로움", "비슷한 색끼리 N개 그룹으로",
)

private val MODES = listOf(Config.MODE_CATEGORY, Config.MODE_COLOR, Config.MODE_AI, Config.MODE_RULES)
private val MODE_NAMES = listOf("카테고리", "색상", "AI", "규칙만")

private enum class Sheet { NONE, AI, SIGNALS, FOLDER_NAMES, RULES, RULES_TEXT }

@Composable
fun OrganizeTab(model: AppModel, openSettings: () -> Unit) {
    model.settingsVersion // recompose on settings writes
    val context = LocalContext.current
    val p = model.prefs
    val mode = p.getString(Config.KEY_MODE, Config.MODE_CATEGORY)
    val modeIdx = MODES.indexOf(mode).coerceAtLeast(0)
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var advanced by remember { mutableStateOf(false) }
    val rules = p.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!!
    val directives = Directives.parse(rules)
    val hasKey = remember(model.settingsVersion) { ApiLlm.fromPrefs(context) != null }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().overScrollVertical().scrollEndHaptic(), contentPadding = PaddingValues(bottom = 12.dp)) {
            item { RiskBanner(model) { model.showDiagnosis = true } }
            item { UpdateBanner(openSettings) }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 12.dp),
                    insideMargin = PaddingValues(16.dp)) {
                    Text(
                        if (!model.moduleActive) "모듈 비활성 · LSPosed에서 켜 주세요" else basis(model),
                        style = MiuixTheme.textStyles.title4,
                    )
                }
            }

            // ---- 1. how apps are grouped
            item { SmallTitle("어떻게 나눌까요") }
            item {
                TabRow(
                    tabs = MODE_NAMES,
                    selectedTabIndex = modeIdx,
                    onTabSelected = { model.setMode(MODES[it]) },
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp),
                )
            }
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    when (MODES[modeIdx]) {
                        Config.MODE_COLOR -> {
                            val idx = COLOR_METHODS.indexOf(p.getString(Config.KEY_COLOR_METHOD, "CENTROID")).coerceAtLeast(0)
                            WindowDropdownPreference(
                                items = COLOR_METHOD_NAMES,
                                selectedIndex = idx,
                                title = "색 읽는 방식",
                                summary = COLOR_METHOD_HELP[idx],
                                onSelectedIndexChange = { model.edit({ putString(Config.KEY_COLOR_METHOD, COLOR_METHODS[it]) }) },
                            )
                            when (COLOR_METHODS[idx]) {
                                "POSITION" -> {
                                    IntSlider(model, Config.KEY_COLOR_X, 50, 0..100, "가로 위치", "%")
                                    IntSlider(model, Config.KEY_COLOR_Y, 50, 0..100, "세로 위치", "%")
                                }
                                "SIMILARITY" -> IntSlider(model, Config.KEY_COLOR_GROUPS, 6, 2..12, "그룹 수", "개")
                            }
                        }
                        Config.MODE_AI -> AiModeCard(model, hasKey, openSettings)
                        Config.MODE_RULES -> Text(
                            "규칙에 맞는 앱만 옮겨요",
                            Modifier.padding(horizontal = 16.dp, vertical = 14.dp), style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        else -> {
                            PrefSwitch(model, Config.KEY_DETAILED, false, "자세히 나누기", "소셜·메신저, 음악·영상 등을 따로")
                            ArrowPreference("분류 근거", summary = signalsSummary(model), onClick = { sheet = Sheet.SIGNALS })
                            ArrowPreference("폴더 이름 바꾸기", onClick = { sheet = Sheet.FOLDER_NAMES })
                        }
                    }
                }
            }

            // ---- 2. the user's own rules (all modes)
            item { SmallTitle("내 규칙") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference("폴더 규칙", summary = folderRuleSummary(rules, mode), onClick = { sheet = Sheet.RULES })
                    ArrowPreference("말로 규칙 만들기", onClick = { sheet = Sheet.AI })
                }
            }

            // ---- 3. order
            item { SmallTitle("정렬") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    val appSorts = listOf(Directives.KEEP, Directives.NAME, Directives.COLOR, Directives.INSTALLED)
                    DirectiveDropdown(model, rules, Directives.D_INSIDE, "폴더 안 앱", appSorts, directives.insideSort)
                    DirectiveDropdown(model, rules, Directives.D_LOOSE, "폴더 밖 앱", appSorts, directives.looseSort)
                    DirectiveDropdown(model, rules, Directives.D_FOLDERS, "폴더 순서",
                        listOf(Directives.KEEP, Directives.NAME, Directives.COLOR, Directives.SIZE), directives.folderSort)
                    DirectiveDropdown(model, rules, Directives.D_PLACEMENT, "폴더와 앱",
                        listOf(Directives.KEEP, Directives.FOLDERS_FIRST, Directives.APPS_FIRST), directives.placement)
                    DirectiveDropdown(model, rules, Directives.D_FOLDER_COLOR, "폴더 색",
                        listOf(Directives.KEEP, Directives.ICON, Directives.CATEGORY, Directives.RESET), directives.folderColor)
                }
            }

            // ---- 4. new installs
            item { SmallTitle("새 앱을 설치하면") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    PrefSwitch(model, Config.KEY_ENABLED, true, "바로 분류", null,
                        enabled = !model.disabledFeatures().contains(Diagnosis.F_LIVE))
                    if (MODES[modeIdx] == Config.MODE_AI) {
                        PrefSwitch(model, Config.KEY_AI_INSTALL, false, "AI로 분류하기",
                            if (hasKey) null else "AI 연결 필요",
                            enabled = hasKey)
                        if (p.getBoolean(Config.KEY_AI_INSTALL, false)) {
                            IntSlider(model, Config.KEY_AI_COOLDOWN, 30, 0..300, "쿨다운", "초")
                            IntSlider(model, Config.KEY_AI_DAILY, 30, 1..100, "하루 최대", "번")
                        }
                    }
                    val modes = listOf(Config.UNCLASSIFIED_FREE, Config.UNCLASSIFIED_FOLDER, Config.UNCLASSIFIED_PAGE, Config.UNCLASSIFIED_KEEP)
                    val names = listOf("규칙 없는 페이지로", "한 폴더에 모으기", "정한 페이지로", "그대로 두기")
                    val cur = modes.indexOf(p.getString(Config.KEY_UNCLASSIFIED, Config.UNCLASSIFIED_FREE)).coerceAtLeast(0)
                    WindowDropdownPreference(
                        items = names,
                        selectedIndex = cur,
                        title = "맞는 폴더가 없으면",
                        onSelectedIndexChange = { model.edit({ putString(Config.KEY_UNCLASSIFIED, modes[it]) }) },
                    )
                    if (modes[cur] == Config.UNCLASSIFIED_FOLDER) {
                        var name by remember { mutableStateOf(p.getString(Config.KEY_UNCLASSIFIED_FOLDER, "기타")!!) }
                        TextField(
                            value = name,
                            onValueChange = { name = it; model.edit({ putString(Config.KEY_UNCLASSIFIED_FOLDER, it.trim()) }) },
                            useLabelAsPlaceholder = true, label = "폴더 이름", singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    if (modes[cur] == Config.UNCLASSIFIED_PAGE) {
                        IntSlider(model, Config.KEY_UNCLASSIFIED_PAGE, 1, 1..10, "페이지", "")
                    }
                    PrefSwitch(model, Config.KEY_SCREEN_OFF_TIDY, true, "페이지 위치도 맞추기", null)
                }
            }

            // ---- 5. rarely changed
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference("고급",
                        onClick = { advanced = !advanced })
                    if (advanced) {
                        IntSlider(model, Config.KEY_MIN_APPS, 2, 1..6, "새 폴더 최소 앱 수", "개")
                        PrefSwitch(model, Config.KEY_CREATE_FOLDERS, true, "폴더가 없으면 새로 만들기", null)
                        PrefSwitch(model, Config.KEY_SKIP_MOVED, false, "손으로 옮긴 앱은 건너뛰기", null)
                        PrefSwitch(model, Config.KEY_CLEAN_STALE, true, "예전 자동 폴더 정리", null)
                    }
                }
            }
        }
    }

    when (sheet) {
        Sheet.AI -> AiSheet(model) { sheet = Sheet.NONE }
        Sheet.SIGNALS -> SignalsSheet(model) { sheet = Sheet.NONE }
        Sheet.FOLDER_NAMES -> FolderNamesSheet(model) { sheet = Sheet.NONE }
        Sheet.RULES -> RulesSheet(model, onText = { sheet = Sheet.RULES_TEXT }) { sheet = Sheet.NONE }
        Sheet.RULES_TEXT -> RulesTextSheet(model) { sheet = Sheet.NONE }
        Sheet.NONE -> Unit
    }
}

/** AI mode: the instruction and one button. Follow-ups happen in the preview it opens. */
@Composable
private fun AiModeCard(model: AppModel, hasKey: Boolean, openSettings: () -> Unit) {
    val p = model.prefs
    var instruction by remember(model.settingsVersion) { mutableStateOf(p.getString(Config.KEY_AI_REQUEST, "")!!) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // Read the app list now so the button goes straight to the AI.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.auto.classify.ai.AiOrganizer.warmUp(model.appContext) }
        }
    }
    var editing by remember { mutableStateOf(false) }
    // HyperOS style: a row showing the instruction; tapping it opens an input dialog.
    ArrowPreference(
        title = "지시",
        summary = instruction.ifBlank { "어떻게 정리할지 적어 주세요" },
        onClick = { editing = true },
    )
    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
        when {
            !hasKey -> TextButton("AI 연결하기", openSettings, Modifier.fillMaxWidth())
            model.aiRunning -> {
                WorkingText(model.aiStage)
                AppearingChips(model.aiFolders)
            }
            else -> TextButton(
                "AI로 정리하기",
                { model.aiOrganize(instruction.trim()) },
                Modifier.fillMaxWidth(),
                enabled = instruction.isNotBlank(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
        model.aiError?.let { ErrorText(it) }
    }
    if (editing) {
        var draft by remember { mutableStateOf(instruction) }
        top.yukonga.miuix.kmp.window.WindowDialog(show = true, title = "어떻게 정리할까요?", onDismissRequest = { editing = false }) {
            androidx.compose.foundation.layout.Column {
                TextField(value = draft, onValueChange = { draft = it }, minLines = 5, modifier = Modifier.fillMaxWidth())
                SheetActions("취소", { editing = false }, "저장", {
                    instruction = draft.trim()
                    model.edit({ putString(Config.KEY_AI_REQUEST, instruction) }, reload = false)
                    editing = false
                })
            }
        }
    }
    PrefSwitch(model, Config.KEY_AI_VERIFY, false, "검증 추가", "토큰 약 2배")
}

/** The one AI input in the preview: adjust, rename or add rules, depending on the mode. */
@Composable
fun AiInput(model: AppModel, layout: org.json.JSONObject?) {
    var text by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    // The request stays until the AI succeeds, so a failure (503 …) can be sent again as is.
    androidx.compose.runtime.LaunchedEffect(model.aiRunning) {
        if (sent && !model.aiRunning) {
            if (model.aiError == null) text = ""
            sent = false
        }
    }
    val send = {
        if (text.isNotBlank() && !model.aiRunning) {
            sent = true
            model.aiTalk(text, layout)
        }
    }
    TextField(
        value = text,
        onValueChange = { text = it },
        useLabelAsPlaceholder = true,
        label = "AI에게 더 요청하기",
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Send),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSend = { send() }),
        trailingIcon = {
            top.yukonga.miuix.kmp.basic.IconButton(onClick = send, enabled = text.isNotBlank()) {
                top.yukonga.miuix.kmp.basic.Icon(
                    top.yukonga.miuix.kmp.icon.MiuixIcons.Send, "보내기",
                    tint = if (text.isNotBlank()) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}

@Composable
private fun PrefSwitch(model: AppModel, key: String, def: Boolean, title: String, summary: String?, enabled: Boolean = true) {
    SwitchPreference(
        checked = model.prefs.getBoolean(key, def),
        onCheckedChange = { v -> model.edit({ putBoolean(key, v) }) },
        title = title,
        summary = summary,
        enabled = enabled,
    )
}

/** Integer slider bound to a pref; saves when released. */
@Composable
fun IntSlider(model: AppModel, key: String, def: Int, range: IntRange, title: String, unit: String) {
    var v by remember(model.settingsVersion) { mutableStateOf(model.prefs.getInt(key, def).toFloat()) }
    SliderPreference(
        value = v,
        onValueChange = { v = it },
        title = title,
        valueText = "${v.toInt()}$unit",
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        onValueChangeFinished = { model.edit({ putInt(key, v.toInt()) }) },
    )
}

@Composable
private fun DirectiveDropdown(
    model: AppModel, rules: String, key: String, title: String, values: List<String>, current: String,
) {
    val idx = values.indexOf(current).coerceAtLeast(0)
    WindowDropdownPreference(
        items = values.map { if (it == Directives.KEEP) "그대로" else it + "순".takeIf { _ -> it in SORT_WORDS }.orEmpty() },
        selectedIndex = idx,
        title = title,
        onSelectedIndexChange = { i ->
            model.edit({ putString(Config.KEY_RULES, setDirective(rules, key, values[i])) })
        },
    )
}

private val SORT_WORDS = setOf(Directives.NAME, Directives.COLOR, Directives.INSTALLED, Directives.SIZE)

/** Rewrites the "!key value" line; "그대로" removes it. */
fun setDirective(rules: String, key: String, value: String): String {
    val lines = rules.split("\n").filterNot { it.trim().startsWith("!$key") }.toMutableList()
    if (value != Directives.KEEP) lines.add("!$key $value")
    return lines.joinToString("\n").trim()
}

fun basis(model: AppModel): String {
    val p = model.prefs
    return when (p.getString(Config.KEY_MODE, Config.MODE_CATEGORY)) {
        Config.MODE_RULES -> "내 규칙으로만 정리"
        Config.MODE_AI -> "AI가 내 지시대로 정리"
        Config.MODE_COLOR -> {
            val idx = COLOR_METHODS.indexOf(p.getString(Config.KEY_COLOR_METHOD, "CENTROID")).coerceAtLeast(0)
            "아이콘 색으로 정리 · ${COLOR_METHOD_NAMES[idx]}"
        }
        else -> "카테고리로 정리"
    }
}

private fun folderRuleSummary(rules: String, mode: String?): String {
    val n = rules.split("\n").count { val t = it.trim(); t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("!") }
    if (n == 0) return "없음"
    return "${n}개"
}

private fun signalsSummary(model: AppModel): String {
    val on = model.prefs.getString(Config.KEY_SIGNALS, Config.DEFAULT_SIGNALS)!!.split(",").map { it.trim() }
    val names = mapOf("roles" to "앱 역할", "play" to "Play 데이터", "model" to "분류 모델", "declared" to "선언 카테고리")
    val n = names.keys.count { it in on }
    return (if (n == 0) "규칙만 사용" else "근거 ${n}가지") + " · 확신도 ${model.prefs.getInt(Config.KEY_THRESHOLD, Config.DEFAULT_THRESHOLD)}%"
}

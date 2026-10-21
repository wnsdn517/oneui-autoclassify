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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.icon.extended.Send
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.auto.classify.ai.ApiLlm
import com.auto.classify.core.Config
import com.auto.classify.core.Directives
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
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

private val MODES = listOf(Config.MODE_CATEGORY, Config.MODE_COLOR, Config.MODE_AI)
private val MODE_NAMES = listOf("카테고리", "색상", "AI")

private enum class Sheet { NONE, SIGNALS, FOLDER_NAMES }

@Composable
fun OrganizeTab(model: AppModel, openSettings: () -> Unit) {
    model.settingsVersion // recompose on settings writes
    val context = LocalContext.current
    val p = model.prefs
    val mode = p.getString(Config.KEY_MODE, Config.MODE_CATEGORY)
    val modeIdx = MODES.indexOf(mode).coerceAtLeast(0)
    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var advanced by remember { mutableStateOf(false) }
    val hasKey = remember(model.settingsVersion) { ApiLlm.fromPrefs(context) != null }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().overScrollVertical().scrollEndHaptic(), contentPadding = PaddingValues(top = LocalTopInset.current + 16.dp, bottom = 12.dp + LocalNavInset.current)) {
            item { RiskBanner(model) { model.showDiagnosis = true } }

            // ---- 1. how apps are grouped (tabs live in the same card as what they change)
            item { SmallTitle("어떻게 나눌까요") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    WindowDropdownPreference(
                        items = MODE_NAMES,
                        selectedIndex = modeIdx,
                        title = "정리 방식",
                        summary = basis(model),
                        onSelectedIndexChange = { model.setMode(MODES[it]) },
                    )
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
                        else -> {
                            PrefSwitch(model, Config.KEY_DETAILED, false, "세분화", "소셜·메신저, 음악·영상 등을 따로")
                            ArrowPreference("확신도", summary = confidenceSummary(model), onClick = { sheet = Sheet.SIGNALS })
                            ArrowPreference("폴더 이름", onClick = { sheet = Sheet.FOLDER_NAMES })
                        }
                    }
                }
            }

            // ---- 2. new installs and advanced (merged, advanced is collapsible)
            item { SmallTitle("새 앱·고급") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    PrefSwitch(model, Config.KEY_ENABLED, true, "설치하면 바로 정리", null,
                        enabled = !model.disabledFeatures().contains(Diagnosis.F_LIVE))
                    val live = p.getBoolean(Config.KEY_ENABLED, true)
                    if (MODES[modeIdx] == Config.MODE_AI) {
                        PrefSwitch(model, Config.KEY_AI_INSTALL, false, "AI에게 물어보기",
                            if (hasKey) null else "AI 연결 필요",
                            enabled = hasKey && live)
                    }
                    val modes = listOf(Config.UNCLASSIFIED_FREE, Config.UNCLASSIFIED_FOLDER, Config.UNCLASSIFIED_PAGE, Config.UNCLASSIFIED_KEEP)
                    val names = listOf("폴더 밖에", "한 폴더에", "정한 페이지에", "그대로")
                    val cur = modes.indexOf(p.getString(Config.KEY_UNCLASSIFIED, Config.UNCLASSIFIED_FREE)).coerceAtLeast(0)
                    WindowDropdownPreference(
                        items = names,
                        selectedIndex = cur,
                        title = "맞는 폴더 없는 앱",
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
                    PrefSwitch(model, Config.KEY_SCREEN_OFF_TIDY, true, "화면 꺼지면 페이지 정리", null, enabled = live)
                    // Advanced settings, collapsed by default
                    ArrowPreference("고급", onClick = { advanced = !advanced })
                    if (advanced) {
                        PrefSwitch(model, Config.KEY_CREATE_FOLDERS, true, "새 폴더 만들기", null)
                        if (p.getBoolean(Config.KEY_CREATE_FOLDERS, true)) {
                            // Stored as min_apps: 1 keeps single-app folders, 2 (default) does not.
                            model.settingsVersion
                            SwitchPreference(
                                checked = model.prefs.getInt(Config.KEY_MIN_APPS, 2) <= 1,
                                onCheckedChange = { v -> model.edit({ putInt(Config.KEY_MIN_APPS, if (v) 1 else 2) }) },
                                title = "앱 1개 유지",
                                summary = "앱이 1개뿐인 폴더도 만들어 둬요",
                            )
                        }
                        PrefSwitch(model, Config.KEY_SKIP_MOVED, false, "직접 옮긴 앱 건드리지 않기", null)
                        PrefSwitch(model, Config.KEY_CLEAN_STALE, true, "안 쓰는 폴더 비우기", null)
                    }
                }
            }
        }
    }

    when (sheet) {
        Sheet.SIGNALS -> SignalsSheet(model) { sheet = Sheet.NONE }
        Sheet.FOLDER_NAMES -> FolderNamesSheet(model) { sheet = Sheet.NONE }
        Sheet.NONE -> Unit
    }
}

/**
 * Longest instruction: it travels with the app list in every request, and Gemini takes about
 * 1M tokens, so this leaves ample room while keeping requests fast.
 */
private const val INSTRUCTION_MAX = 20_000

/** AI mode: the instruction and one button. Follow-ups happen in the preview it opens. */
@Composable
private fun AiModeCard(model: AppModel, hasKey: Boolean, openSettings: () -> Unit) {
    val p = model.prefs
    // The typed instruction survives tab switches and restarts (draft pref), so it is never lost.
    var instruction by rememberSaveable(model.settingsVersion) {
        mutableStateOf(p.getString(Config.KEY_AI_DRAFT, "")!!.ifBlank { p.getString(Config.KEY_AI_REQUEST, "")!! })
    }
    androidx.compose.runtime.LaunchedEffect(instruction) {
        if (instruction != p.getString(Config.KEY_AI_DRAFT, "")) {
            p.edit().putString(Config.KEY_AI_DRAFT, instruction).apply()
        }
    }
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
    var memoOpen by remember { mutableStateOf(false) }
    val memoCount = com.auto.classify.ai.AiMemo.load(p).size
    ArrowPreference(
        title = "AI 메모",
        summary = if (memoCount == 0) "없음" else "${memoCount}개",
        onClick = { memoOpen = true },
    )
    if (memoOpen) {
        var draft by remember { mutableStateOf(p.getString(Config.KEY_AI_MEMO, "")!!) }
        top.yukonga.miuix.kmp.window.WindowDialog(show = true, title = "AI 메모", onDismissRequest = { memoOpen = false }) {
            androidx.compose.foundation.layout.Column {
                val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.4f).dp
                androidx.compose.foundation.layout.Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                    TextField(value = draft, onValueChange = { draft = it }, minLines = 5, modifier = Modifier.fillMaxWidth(),
                        label = "한 줄에 하나", useLabelAsPlaceholder = true)
                }
                SheetActions("취소", { memoOpen = false }, "저장", {
                    model.edit({ putString(Config.KEY_AI_MEMO, draft.trim()) }, reload = false)
                    memoOpen = false
                })
            }
        }
    }
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
        AiReply(model)
    }
    if (editing) {
        // The field writes straight to `instruction`, so Back or a tap outside never loses typing; only 취소 reverts.
        val opened = remember { instruction }
        val save = {
            instruction = instruction.trim()
            model.edit({ putString(Config.KEY_AI_REQUEST, instruction) }, reload = false)
            editing = false
        }
        top.yukonga.miuix.kmp.window.WindowDialog(show = true, title = "어떻게 정리할까요?", onDismissRequest = save) {
            androidx.compose.foundation.layout.Column {
                // The field scrolls inside a bounded area, so the buttons below never get pushed off screen.
                val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.4f).dp
                androidx.compose.foundation.layout.Column(
                    Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                ) {
                    TextField(
                        value = instruction,
                        onValueChange = { if (it.length <= INSTRUCTION_MAX) instruction = it },
                        minLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("${instruction.length} / $INSTRUCTION_MAX", Modifier.padding(top = 4.dp), style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                SheetActions("취소", { instruction = opened; editing = false }, "저장", save)
            }
        }
    }
}

/** The one AI input in the preview: adjust, rename or add rules, depending on the mode. */
@Composable
fun AiInput(model: AppModel, layout: org.json.JSONObject?) {
    // Restored from the last unsent draft, so closing the preview never loses typed text.
    var text by rememberSaveable { mutableStateOf(model.aiDraft) }
    var sent by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(text) { model.aiDraft = text }
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
fun PrefSwitch(model: AppModel, key: String, def: Boolean, title: String, summary: String?, enabled: Boolean = true) {
    model.settingsVersion // redraw when any setting changes (prefs aren't observable)
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
fun IntSlider(model: AppModel, key: String, def: Int, range: IntRange, title: String, unit: String, reload: Boolean = true) {
    var v by remember(model.settingsVersion) { mutableStateOf(model.prefs.getInt(key, def).toFloat()) }
    SliderPreference(
        value = v,
        onValueChange = { v = it },
        title = title,
        valueText = "${v.toInt()}$unit",
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0),
        onValueChangeFinished = { model.edit({ putInt(key, v.toInt()) }, reload) },
    )
}

/** Rewrites the "!key value" line; "그대로" removes it. */
fun setDirective(rules: String, key: String, value: String): String {
    val lines = rules.split("\n").filterNot { it.trim().startsWith("!$key") }.toMutableList()
    if (value != Directives.KEEP) lines.add("!$key $value")
    return lines.joinToString("\n").trim()
}

fun basis(model: AppModel): String {
    val p = model.prefs
    return when (p.getString(Config.KEY_MODE, Config.MODE_CATEGORY)) {
        Config.MODE_AI -> "AI가 내 지시대로 정리"
        Config.MODE_COLOR -> {
            val idx = COLOR_METHODS.indexOf(p.getString(Config.KEY_COLOR_METHOD, "CENTROID")).coerceAtLeast(0)
            "아이콘 색으로 정리 · ${COLOR_METHOD_NAMES[idx]}"
        }
        else -> "카테고리로 정리"
    }
}

private fun confidenceSummary(model: AppModel): String =
    "${model.prefs.getInt(Config.KEY_THRESHOLD, Config.DEFAULT_THRESHOLD)}% 이상일 때만 이동"

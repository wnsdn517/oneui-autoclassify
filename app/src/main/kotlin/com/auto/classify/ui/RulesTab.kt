package com.auto.classify.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.auto.classify.ai.ApiLlm
import com.auto.classify.core.Config
import com.auto.classify.core.Directives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** Rules and excluded apps in one place: GUI list, AI builder, templates, text edit. */
@Composable
fun RulesTab(model: AppModel) {
    val context = LocalContext.current
    model.settingsVersion // re-read rules and exclusions on changes
    var reload by remember { mutableStateOf(0) }
    var textEdit by remember { mutableStateOf(false) }
    var aiSheet by remember { mutableStateOf(false) }
    val raw = remember(reload, model.settingsVersion) { model.prefs.getString(Config.KEY_RULES, Config.DEFAULT_RULES)!! }
    val others = remember(raw) { raw.lines().map { it.trim() }.filter { it.startsWith("#") || it.startsWith("!") } }
    val items = remember(raw) {
        mutableStateListOf<RuleItem>().apply {
            raw.lines().mapNotNullTo(this) { l ->
                RuleItem.parse(l.trim()).takeIf { !l.trim().startsWith("#") && !l.trim().startsWith("!") }
            }
        }
    }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { launchableApps(context) } }
    val labels = remember(apps) { apps.toMap() }
    val rulesText = items.joinToString("\n") { it.toLine() }
    var warnings by remember { mutableStateOf<List<RuleWarning?>>(emptyList()) }
    LaunchedEffect(rulesText, apps) {
        if (apps.isNotEmpty()) warnings = withContext(Dispatchers.IO) { ruleWarnings(context, rulesText, apps) }
    }
    val hasKey = remember(model.settingsVersion) { ApiLlm.fromPrefs(context) != null }
    val mode = model.prefs.getString(Config.KEY_MODE, Config.MODE_CATEGORY)
    val directives = remember(raw) { Directives.parse(raw) }
    // Rules switched off sit aside (not applied) until switched back on.
    val offRaw = remember(model.settingsVersion) { model.prefs.getString(Config.KEY_RULES_OFF, "")!! }
    val offItems = remember(offRaw) { offRaw.lines().mapNotNull { RuleItem.parse(it.trim()) } }

    fun save() {
        model.saveRules((others + items.map { it.toLine() }).joinToString("\n"))
    }
    fun saveOff(lines: List<String>) {
        model.edit({ putString(Config.KEY_RULES_OFF, lines.joinToString("\n")) }, reload = false)
    }
    fun toggleOff(item: RuleItem) {
        items.remove(item)
        save()
        saveOff((offRaw.lines().filter { it.isNotBlank() } + item.toLine()).distinct())
    }
    fun toggleOn(line: String) {
        RuleItem.parse(line.trim())?.let { items.add(it) }
        save()
        saveOff(offRaw.lines().filter { it.trim() != line.trim() })
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().overScrollVertical().scrollEndHaptic(),
            contentPadding = PaddingValues(top = LocalTopInset.current, bottom = 12.dp + LocalNavInset.current),
        ) {
            item { RiskBanner(model) { model.showDiagnosis = true } }

            item { SmallTitle("폴더 규칙") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    ArrowPreference(
                        "AI로 규칙 만들기",
                        summary = if (hasKey) "말로 설명하면 규칙으로 바꿔줘요" else "AI 연결 필요 (설정 탭)",
                        enabled = hasKey,
                        onClick = { aiSheet = true },
                    )
                }
            }
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        RuleRows(model, items, warnings, labels,
                            onEdit = { index, item -> model.ruleEditor = index to item },
                            onSave = ::save,
                            onToggle = ::toggleOff)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 4.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = { textEdit = true }) {
                            Icon(MiuixIcons.Edit, "텍스트로 편집", tint = MiuixTheme.colorScheme.primary)
                        }
                    }
                }
            }

            if (offItems.isNotEmpty()) {
                item { SmallTitle("꺼진 규칙") }
                item {
                    Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            offItems.forEach { item ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        ArrowPreference(
                                            title = ruleSubject(item, labels),
                                            summary = "꺼짐 · 눌러서 켜기",
                                            onClick = { toggleOn(item.toLine()) },
                                        )
                                    }
                                    Switch(
                                        checked = false,
                                        onCheckedChange = { toggleOn(item.toLine()) },
                                        modifier = Modifier.padding(end = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { SmallTitle("정렬") }
            item {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    val appSorts = listOf(Directives.KEEP, Directives.NAME, Directives.COLOR, Directives.INSTALLED)
                    DirectiveDropdown(model, raw, Directives.D_INSIDE, "폴더 안 앱", appSorts, directives.insideSort)
                    DirectiveDropdown(model, raw, Directives.D_LOOSE, "폴더 밖 앱", appSorts, directives.looseSort)
                    val aiOrder = mode == Config.MODE_AI && model.prefs.getString(Config.KEY_AI_ORDER, "[]")!!.trim()
                        .let { it != "[]" && it.isNotEmpty() }
                    DirectiveDropdown(model, raw, Directives.D_FOLDERS, "폴더 순서",
                        listOf(Directives.KEEP, Directives.NAME, Directives.COLOR, Directives.SIZE), directives.folderSort,
                        enabled = !aiOrder, summary = if (aiOrder) "AI가 정한 순서" else null)
                    DirectiveDropdown(model, raw, Directives.D_PLACEMENT, "배치",
                        listOf(Directives.KEEP, Directives.FOLDERS_FIRST, Directives.APPS_FIRST), directives.placement)
                    DirectiveDropdown(model, raw, Directives.D_FOLDER_COLOR, "폴더 색",
                        listOf(Directives.KEEP, Directives.ICON, Directives.CATEGORY, Directives.RAINBOW, Directives.RESET), directives.folderColor)
                }
            }
        }
    }

    // Opened from here, the organize tab link, or the bottom action button.
    model.ruleEditor?.let { (index, item) ->
        RuleDialog(model, item, apps, isNew = index < 0, onDelete = {
            if (index >= 0) {
                items.removeAt(index)
                save()
            }
            model.ruleEditor = null
        }, onSave = {
            if (index < 0) items.add(it) else items[index] = it
            save()
            model.ruleEditor = null
        }, onClose = { model.ruleEditor = null })
    }
    if (textEdit) RulesTextSheet(model) { textEdit = false; reload++ }
    if (aiSheet) AiSheet(model) { aiSheet = false }
}

/** Sort dropdown bound to a "!key value" rule line. */
@Composable
private fun DirectiveDropdown(
    model: AppModel, rules: String, key: String, title: String, values: List<String>, current: String,
    enabled: Boolean = true, summary: String? = null,
) {
    val idx = values.indexOf(current).coerceAtLeast(0)
    WindowDropdownPreference(
        items = values.map { if (it == Directives.KEEP) "그대로" else it + "순".takeIf { _ -> it in SORT_WORDS }.orEmpty() },
        selectedIndex = idx,
        title = title,
        summary = summary,
        enabled = enabled,
        onSelectedIndexChange = { i ->
            model.edit({ putString(Config.KEY_RULES, setDirective(rules, key, values[i])) })
        },
    )
}

private val SORT_WORDS = setOf(Directives.NAME, Directives.COLOR, Directives.INSTALLED, Directives.SIZE)


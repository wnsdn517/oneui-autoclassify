package com.auto.classify.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auto.classify.core.Config
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun BackupTab(model: AppModel) {
    model.settingsVersion // auto-backup toggle and exclusions redraw the list
    var open by remember { mutableStateOf<BackupInfo?>(null) }
    var expandedBackups by remember { mutableStateOf(setOf<String>()) }
    val off = model.disabledFeatures().contains(Diagnosis.F_BACKUP)
    // Swipe-deleted rows hide at once; the launcher delete fires after the undo window.
    var hidden by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val list = model.backups
    val snackbar = remember { SnackbarHostState() }
    // Forget hidden entries once the launcher list refreshes without them.
    LaunchedEffect(list) {
        if (list != null) {
            val names = list.map { it.name }.toSet()
            hidden = hidden intersect names
            if (pendingDelete != null && pendingDelete !in names) pendingDelete = null
        }
    }
    // Undo snackbar: the delete goes out only when it is not undone before the snackbar times out.
    LaunchedEffect(pendingDelete) {
        val name = pendingDelete ?: return@LaunchedEffect
        when (snackbar.showSnackbar("백업을 삭제했어요", actionLabel = "실행 취소")) {
            SnackbarResult.ActionPerformed -> { pendingDelete = null; hidden = hidden - name }
            SnackbarResult.Dismissed -> { model.send("delete", name); pendingDelete = null }
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().overScrollVertical().scrollEndHaptic(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp + LocalTopInset.current, bottom = 12.dp + LocalNavInset.current)) {
            item { RiskBanner(model) { model.showDiagnosis = true } }
            val shown = (list ?: emptyList()).filter { it.name !in hidden }
            when {
                off -> item { Hint("백업 기능이 꺼져 있어요 (진단에서 문제 발견)") }
                list == null && model.backupsUnavailable -> item { Hint("One UI 홈에 연결되지 않았어요") }
                list == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { InfiniteProgressIndicator() } }
                shown.isEmpty() -> item { Hint("아직 백업이 없어요") }
                else -> items(shown, key = { it.name }) { b ->
                    // No horizontal swipe on rows: it would block the page swipe. Delete lives in the preview.
                    // Tap expands the layout right here; "자세히" opens the full sheet.
                    val expanded = b.name in expandedBackups
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        onClick = { expandedBackups = if (expanded) expandedBackups - b.name else expandedBackups + b.name },
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(prettyTime(b.name), Modifier.weight(1f), style = MiuixTheme.textStyles.title3)
                            if (b.instructions != null) Text("지시 포함", style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        if (b.layout != null) BackupStats(b.layout, Modifier.padding(top = 10.dp))
                        AnimatedVisibility(expanded && b.layout != null) {
                            Column {
                                if (b.layout != null) LayoutPages(b.layout, Modifier.padding(top = 8.dp), maxGridHeight = 180.dp, stacked = true, labels = false)
                                TextButton("자세히 · 되돌리기", { open = b }, Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                        }
                    }
                }
            }
        }
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).padding(bottom = LocalNavInset.current + 12.dp, start = 24.dp, end = 24.dp),
        )
    }
    open?.let { b ->
        BackupSheet(
            model, b,
            onDelete = { hidden = hidden + b.name; pendingDelete = b.name; open = null },
        ) { open = null }
    }
}

@Composable
private fun BackupSheet(model: AppModel, b: BackupInfo, onDelete: () -> Unit, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    // Off unless the user asks: a restore brings back the layout only, unless this is ticked.
    var withInstructions by remember { mutableStateOf(false) }
    // Same grid sizing as the main preview sheet, so the backup reads the same way.
    val gridMax = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.92f - 330).coerceAtLeast(280f).dp
    WindowBottomSheet(show = true, title = prettyTime(b.name), onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 900.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (b.layout != null) BackupStats(b.layout, Modifier.padding(vertical = 8.dp))
                LayoutPages(b.layout, Modifier.padding(vertical = 8.dp), maxGridHeight = gridMax, stacked = true, labels = false)
            }
            Text("이 백업 삭제", Modifier.fillMaxWidth().clickable(onClick = onDelete).padding(vertical = 10.dp),
                textAlign = TextAlign.Center, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.error)
            SheetActions("닫기", onClose, "이 시점으로 되돌리기", { confirm = true })
        }
    }
    if (confirm) {
        WindowDialog(show = true, title = "이 시점으로 되돌릴까요?",
            summary = "지금 상태도 백업해 둬요",
            onDismissRequest = { confirm = false }) {
            if (b.instructions != null) {
                SwitchPreference(checked = withInstructions, onCheckedChange = { withInstructions = it },
                    title = "지시도 함께 되돌리기", summary = "규칙·제외·AI 지시를 이 백업 때 값으로 바꿔요")
            } else {
                Hint("이 백업에는 지시가 없어요 (지시 저장 전에 만든 백업)")
            }
            SheetActions("취소", { confirm = false }, "되돌리기", {
                confirm = false
                onClose()
                val instructions = b.instructions
                if (withInstructions && instructions != null) {
                    SettingsBackup.importInstructions(model.appContext, instructions)
                }
                model.request("restore", b.name)
            })
        }
    }
}

fun prettyTime(name: String): String = runCatching {
    val d = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).parse(name)!!
    SimpleDateFormat("M월 d일 (E) HH:mm", Locale.KOREAN).format(d)
}.getOrDefault(name)

/** Three big numbers with small labels: pages, folders, apps outside folders. */
@Composable
private fun BackupStats(layout: JSONObject, modifier: Modifier = Modifier) {
    val pages = layout.optJSONArray("pages") ?: return
    var folders = 0
    var loose = 0
    for (p in 0 until pages.length()) {
        val items = pages.optJSONArray(p) ?: continue
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("type") == "folder") folders++ else loose++
        }
    }
    Row(modifier.fillMaxWidth()) {
        listOf("페이지" to pages.length(), "폴더" to folders, "단독 앱" to loose).forEach { (label, n) ->
            Column(Modifier.weight(1f)) {
                Text("$n", style = MiuixTheme.textStyles.title4)
                Text(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

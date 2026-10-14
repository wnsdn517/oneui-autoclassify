package com.auto.classify.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun BackupTab(model: AppModel) {
    var open by remember { mutableStateOf<BackupInfo?>(null) }
    val off = model.disabledFeatures().contains(Diagnosis.F_BACKUP)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().overScrollVertical().scrollEndHaptic(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp + LocalNavInset.current)) {
            item { RiskBanner(model) { model.showDiagnosis = true } }
            val list = model.backups
            when {
                off -> item { Hint("백업 기능이 꺼져 있어요 (진단에서 문제 발견)") }
                list == null && model.backupsUnavailable -> item { Hint("One UI 홈에 연결되지 않았어요") }
                list == null -> item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { InfiniteProgressIndicator() } }
                list.isEmpty() -> item { Hint("아직 백업이 없어요") }
                else -> items(list, key = { it.name }) { b ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        ArrowPreference(title = prettyTime(b.name), summary = summarize(b.layout), onClick = { open = b })
                    }
                }
            }
        }
    }
    open?.let { BackupSheet(model, it) { open = null } }
}

@Composable
private fun BackupSheet(model: AppModel, b: BackupInfo, onClose: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    // Off unless the user asks: a restore brings back the layout only, unless this is ticked.
    var withInstructions by remember { mutableStateOf(false) }
    WindowBottomSheet(show = true, title = prettyTime(b.name), onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 700.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Hint(summarize(b.layout))
                LayoutPages(b.layout, Modifier.padding(vertical = 8.dp))
            }
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

fun summarize(layout: JSONObject?): String {
    if (layout == null) return ""
    var folders = 0
    var loose = 0
    val pages = layout.optJSONArray("pages") ?: return ""
    for (p in 0 until pages.length()) {
        val items = pages.optJSONArray(p) ?: continue
        for (i in 0 until items.length()) {
            if (items.optJSONObject(i)?.optString("type") == "folder") folders++ else loose++
        }
    }
    return "${pages.length()}페이지 · 폴더 ${folders}개 · 폴더 밖 앱 ${loose}개"
}

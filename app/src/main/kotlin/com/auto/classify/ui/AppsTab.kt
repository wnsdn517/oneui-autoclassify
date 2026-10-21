package com.auto.classify.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.auto.classify.ClassifierProvider
import com.auto.classify.core.ColorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

/** Per-app result of the current settings. */
data class AppRow(
    val pkg: String, val label: String, val target: String?, val reason: String?, val mainColor: Int?,
)

/** Last classified list, with the settings version and package set it was built from. Reused on re-entry. */
private var rowsCache: Triple<Int, List<String>, List<AppRow>>? = null

@Composable
fun AppsTab(model: AppModel) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf<List<AppRow>?>(rowsCache?.takeIf { it.first == model.settingsVersion }?.third) }
    var open by remember { mutableStateOf<AppRow?>(null) }
    val version = model.settingsVersion
    // Reading the version recomposes the list when a name or icon changes.
    AppOverrides.version

    var classifying by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        // Names first (fast), then categories and colors (slower).
        val apps = withContext(Dispatchers.IO) { launchableApps(context) }
        val pkgs = apps.map { it.first }
        // Same settings and same installed apps as the last build: show it at once, no reclassify.
        rowsCache?.takeIf { it.first == version && it.second == pkgs }?.let { rows = it.third; return@LaunchedEffect }
        if (rows == null) rows = apps.map { AppRow(it.first, it.second, null, null, null) }
        classifying = true
        val loaded = withContext(Dispatchers.IO) { loadRows(context, apps) }
        rows = loaded
        rowsCache = Triple(version, pkgs, loaded)
        classifying = false
    }
    model.settingsVersion
    val excluded = model.excluded()

    // Search and tabs scroll with the list (under the title bar's blur, not fixed above it).
    val list = rows
    val collator = remember { java.text.Collator.getInstance(java.util.Locale.KOREAN) }
    // Shown names (user-set names win), so the list is sorted and searched by what is on screen.
    val names = remember(list, AppOverrides.version) {
        list.orEmpty().associate { it.pkg to (AppOverrides.name(context, it.pkg) ?: it.label) }
    }
    // Excluded apps are one list: you excluded them, or a rule keeps them out. The source is a tag.
    fun exclusionOf(r: AppRow): String? = when {
        r.pkg in excluded -> "직접 제외"
        r.reason?.contains("제외") == true -> "규칙 제외"
        else -> null
    }
    val shown = (list ?: emptyList()).filter { r ->
        val label = names[r.pkg] ?: r.label
        (filter == 0 || exclusionOf(r) != null) &&
            (query.isBlank() || label.contains(query, true) || r.pkg.contains(query, true))
    }.sortedWith { a, b -> collator.compare(names[a.pkg] ?: a.label, names[b.pkg] ?: b.label) }
    LazyColumn(
        Modifier.fillMaxSize().overScrollVertical().scrollEndHaptic(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = LocalTopInset.current + 6.dp, bottom = 12.dp + LocalNavInset.current),
    ) {
        item {
            TextField(value = query, onValueChange = { query = it }, useLabelAsPlaceholder = true, label = "앱 검색", singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
        }
        item {
            TabRow(listOf("전체", "제외됨"), filter, { filter = it }, Modifier.padding(vertical = 4.dp))
        }
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { InfiniteProgressIndicator() } }
        } else {
            item {
                Hint(if (classifying) "앱 ${shown.size}개 · 분류하는 중…" else "앱 ${shown.size}개 · 누르면 자세히 보기")
            }
            items(shown, key = { it.pkg }) { r ->
                val exclusion = exclusionOf(r)
                Card(Modifier.animateItem().fillMaxWidth().padding(vertical = 6.dp), onClick = { open = r },
                    pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
                    insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(r.pkg, Modifier.size(42.dp))
                        Text(names[r.pkg] ?: r.label, Modifier.weight(1f).padding(horizontal = 12.dp), style = MiuixTheme.textStyles.main,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        val tag = when {
                            exclusion != null -> exclusion
                            classifying && r.reason == null -> "…"
                            r.target == null -> "그대로"
                            r.target == "(폴더밖)" -> "폴더 밖"
                            else -> r.target
                        }
                        Text(
                            tag,
                            Modifier.clip(RoundedCornerShape(50))
                                .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MiuixTheme.textStyles.footnote1,
                            color = if (r.target == null || exclusion != null) MiuixTheme.colorScheme.onSurfaceVariantSummary
                                else MiuixTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
    open?.let { r -> AppDetailSheet(model, r, r.pkg in excluded) { open = null } }
}

/** "pkg@updateTime" to dominant color of the real icon (custom icons do not change it). Decoding icons is the slow part of a reload. */
private val colorCache = java.util.concurrent.ConcurrentHashMap<String, Int?>()

private fun loadRows(context: Context, apps: List<Pair<String, String>>): List<AppRow> {
    val pkgs = apps.map { it.first }.toTypedArray()
    val labels = apps.map { it.second }.toTypedArray()
    val b = ClassifierProvider.classify(context, pkgs, labels, true)
    val targets = b.getStringArray("targets")
    val reasons = b.getStringArray("reasons")
    val pm = context.packageManager
    return apps.mapIndexed { i, (pkg, label) ->
        val updated = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        val key = "$pkg@$updated"
        val color = colorCache.getOrPut(key) {
            runCatching {
                ColorEngine.representative(ColorEngine.pixels(pm.getApplicationIcon(pkg)), ColorEngine.Method.CENTROID, 0.5f, 0.5f)
            }.getOrNull()
        }
        AppRow(pkg, label, targets?.getOrNull(i), reasons?.getOrNull(i), color)
    }
}

/** Name, icon, folder and exclusion for one app. Opened from the UI tweak tab. */
@Composable
private fun AppDetailSheet(model: AppModel, row: AppRow, excluded: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Reading the version subscribes this sheet to override changes.
    AppOverrides.version
    // Picked image waits here until the crop dialog saves it.
    var cropSource by remember { mutableStateOf<Bitmap?>(null) }
    val pickIcon = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            cropSource = withContext(Dispatchers.IO) { AppOverrides.decode(context, uri) }
        }
    }
    val displayName = AppOverrides.name(context, row.pkg) ?: row.label

    WindowBottomSheet(show = true, onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 700.dp).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(row.pkg, Modifier.size(64.dp))
                Text(displayName, Modifier.padding(top = 10.dp), style = MiuixTheme.textStyles.title3)
                Text(row.pkg, Modifier.padding(top = 2.dp), style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Card {
                ArrowPreference(
                    title = "이름 바꾸기",
                    summary = AppOverrides.name(context, row.pkg)?.let { "기본: ${row.label}" },
                    onClick = { renaming = true },
                )
                if (renaming) {
                    var text by remember { mutableStateOf(displayName) }
                    WindowDialog(show = true, title = "앱 이름", summary = "비우면 기본 이름으로 돌아가요",
                        onDismissRequest = { renaming = false }) {
                        TextField(value = text, onValueChange = { text = it }, singleLine = true, useLabelAsPlaceholder = true,
                            label = row.label, modifier = Modifier.fillMaxWidth())
                        SheetActions("취소", { renaming = false }, "저장", {
                            // Same as the real name means no override.
                            AppOverrides.setName(context, row.pkg, if (text.trim() == row.label) "" else text)
                            AppOverrides.changed()
                            renaming = false
                        })
                    }
                }
                ArrowPreference(
                    title = "아이콘 바꾸기",
                    summary = if (AppOverrides.hasIcon(context, row.pkg)) "직접 고른 아이콘" else null,
                    onClick = { pickIcon.launch("image/*") },
                )
                if (AppOverrides.hasIcon(context, row.pkg)) {
                    ArrowPreference(
                        title = "기본 아이콘으로",
                        onClick = {
                            AppOverrides.resetIcon(context, row.pkg)
                            AppOverrides.changed()
                        },
                    )
                }
            }
            cropSource?.let { src ->
                IconCropDialog(src, onDismiss = { cropSource = null }) { cropped ->
                    scope.launch {
                        withContext(Dispatchers.IO) { AppOverrides.setIconBitmap(context, row.pkg, cropped) }
                        AppOverrides.changed()
                        cropSource = null
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card {
                ArrowPreference(
                    title = "폴더 직접 정하기",
                    
                    onClick = { picking = true },
                )
                SwitchPreference(checked = excluded, onCheckedChange = { model.setExcluded(row.pkg, it) },
                    title = "정리에서 빼기")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (picking) {
        MoveDialog(row.label, model.folders, onPick = { folder ->
            picking = false
            model.assign(row.pkg, folder, replan = false)
            model.toast(if (folder == null) "${row.label}: 폴더 밖에 두도록 규칙을 추가했어요" else "${row.label}: '$folder' 폴더 규칙을 추가했어요")
        }) { picking = false }
    }
}

/** Color dot with a thin outline so black/white icons still show on any background. */
@Composable
fun Swatch(color: Int, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.6f), CircleShape)
            .background(Color(color))
    )
}


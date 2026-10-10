package com.auto.classify.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.auto.classify.ClassifierProvider
import com.auto.classify.core.ColorEngine
import com.auto.classify.core.Config
import com.auto.classify.core.PlayModel
import kotlinx.coroutines.Dispatchers
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
import java.text.DateFormat
import java.util.Date

/** Per-app result of the current settings. */
data class AppRow(
    val pkg: String, val label: String, val target: String?, val reason: String?, val mainColor: Int?,
)

@Composable
fun AppsTab(model: AppModel) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }
    var rows by remember { mutableStateOf<List<AppRow>?>(null) }
    var open by remember { mutableStateOf<AppRow?>(null) }
    val version = model.settingsVersion

    var classifying by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        // Names first (fast), then categories and colors (slower).
        val apps = withContext(Dispatchers.IO) { launchableApps(context) }
        if (rows == null) rows = apps.map { AppRow(it.first, it.second, null, null, null) }
        classifying = true
        rows = withContext(Dispatchers.IO) { loadRows(context, apps) }
        classifying = false
    }
    model.settingsVersion
    val excluded = model.excluded()

    Column(Modifier.fillMaxSize()) {
        TextField(value = query, onValueChange = { query = it }, useLabelAsPlaceholder = true, label = "앱 검색", singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        TabRow(listOf("전체", "제외됨"), filter, { filter = it }, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        val list = rows
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { InfiniteProgressIndicator() }
        } else {
            val shown = list.filter {
                (filter == 0 || it.pkg in excluded) &&
                    (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true))
            }
            LazyColumn(Modifier.fillMaxSize().overScrollVertical().scrollEndHaptic(), contentPadding = PaddingValues(12.dp)) {
                item {
                    Hint(if (classifying) "앱 ${shown.size}개 · 분류하는 중…"
                        else "앱 ${shown.size}개 · 누르면 자세히 보기")
                }
                items(shown, key = { it.pkg }) { r ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), onClick = { open = r },
                        pressFeedbackType = top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink,
                        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(r.pkg, Modifier.size(42.dp))
                            Text(r.label, Modifier.weight(1f).padding(horizontal = 12.dp), style = MiuixTheme.textStyles.main,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            r.mainColor?.let { c -> Swatch(c, 10.dp) }
                            val tag = when {
                                r.pkg in excluded -> "제외"
                                classifying && r.reason == null -> "…"
                                r.target == null -> "그대로"
                                r.target == "(폴더밖)" -> "폴더 밖"
                                else -> r.target
                            }
                            Text(
                                tag,
                                Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50))
                                    .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MiuixTheme.textStyles.footnote1,
                                color = if (r.target == null || r.pkg in excluded) MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    else MiuixTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
    open?.let { r -> AppDetailSheet(model, r, r.pkg in excluded) { open = null } }
}

private fun loadRows(context: Context, apps: List<Pair<String, String>>): List<AppRow> {
    val pkgs = apps.map { it.first }.toTypedArray()
    val labels = apps.map { it.second }.toTypedArray()
    val b = ClassifierProvider.classify(context, pkgs, labels, true)
    val targets = b.getStringArray("targets")
    val reasons = b.getStringArray("reasons")
    val pm = context.packageManager
    return apps.mapIndexed { i, (pkg, label) ->
        val color = runCatching {
            ColorEngine.representative(ColorEngine.pixels(pm.getApplicationIcon(pkg)), ColorEngine.Method.CENTROID, 0.5f, 0.5f)
        }.getOrNull()
        AppRow(pkg, label, targets?.getOrNull(i), reasons?.getOrNull(i), color)
    }
}

/** Method name, a rounded swatch, the group it lands in and the hex value. */
@Composable
private fun ColorTile(method: String, color: Int?, group: String) {
    Column(Modifier.padding(end = 8.dp).width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .border(1.dp, MiuixTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .background(if (color != null) Color(color) else MiuixTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (color == null) Text(group, style = MiuixTheme.textStyles.footnote2)
        }
        Text(method, Modifier.padding(top = 6.dp), style = MiuixTheme.textStyles.footnote1, maxLines = 1)
        if (color != null) {
            Text(group, style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
}

@Composable
private fun AppDetailSheet(model: AppModel, row: AppRow, excluded: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var detail by remember { mutableStateOf<AppDetail?>(null) }
    LaunchedEffect(row.pkg) { detail = withContext(Dispatchers.IO) { loadDetail(context, model, row) } }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var more by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val isColor = model.prefs.getString(Config.KEY_MODE, Config.MODE_CATEGORY) == Config.MODE_COLOR

    WindowBottomSheet(show = true, onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 700.dp).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AppIcon(row.pkg, Modifier.size(64.dp))
                Text(row.label, Modifier.padding(top = 10.dp), style = MiuixTheme.textStyles.title3)
                Text(row.pkg, Modifier.padding(top = 2.dp), style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            val d = detail
            if (d == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { InfiniteProgressIndicator() }
                return@Column
            }

            // What happens to this app, in two big values.
            Card(insideMargin = PaddingValues(16.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Big("카테고리", d.category ?: "그대로 둠", Modifier.weight(1f), highlight = !isColor)
                    Big("색상", d.colorGroup ?: "-", Modifier.weight(1f), highlight = isColor, swatch = d.colors.firstOrNull()?.second)
                }
                val why = (if (isColor) d.colorReason else d.categoryReason)
                if (!why.isNullOrBlank()) {
                    Text("근거: $why", Modifier.padding(top = 12.dp), style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
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
            Spacer(Modifier.height(12.dp))
            Card {
                ArrowPreference(
                    title = "자세히",
                    summary = if (more) "접기" else "분류 데이터 · 아이콘 색 · 앱 정보",
                    onClick = { more = !more },
                )
            }
            if (more) {
                Spacer(Modifier.height(12.dp))
                Card(insideMargin = PaddingValues(16.dp)) {
                    Info("Play 스토어", d.play ?: "데이터 없음")
                    Info("앱이 선언한 카테고리", d.declared ?: "없음")
                    Info("버전", d.version)
                    Info("설치", d.installed)
                    Info("시스템 앱", if (d.system) "예" else "아니요")
                    Text("아이콘 색", Modifier.padding(top = 12.dp, bottom = 8.dp), style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        d.colors.forEach { (name, c, group) -> ColorTile(name, c, group) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Card {
                    d.sections.filter { it.second.isNotEmpty() }.forEach { (title, items) ->
                        ArrowPreference(
                            title = "$title ${items.size}개",
                            summary = if (title in expanded) "접기" else null,
                            onClick = { if (items.isNotEmpty()) expanded = if (title in expanded) expanded - title else expanded + title },
                        )
                        if (title in expanded) items.forEach {
                            Text(it.substringAfterLast('.').ifEmpty { it }, Modifier.padding(horizontal = 16.dp, vertical = 3.dp),
                                style = MiuixTheme.textStyles.footnote1)
                        }
                    }
                }
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

@Composable
private fun Big(title: String, value: String, modifier: Modifier, highlight: Boolean, swatch: Int? = null) {
    Column(modifier) {
        Text(title + if (highlight) " · 지금 기준" else "", style = MiuixTheme.textStyles.footnote1,
            color = if (highlight) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (swatch != null) {
                Swatch(swatch, 18.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(value, style = MiuixTheme.textStyles.title3)
        }
    }
}

@Composable
private fun Info(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, Modifier.weight(1f), style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(value, style = MiuixTheme.textStyles.body2)
    }
}

private class AppDetail(
    val version: String, val targetSdk: Int, val minSdk: Int, val installed: String, val installer: String?,
    val system: Boolean,
    val category: String?, val categoryReason: String?, val colorGroup: String?, val colorReason: String?,
    val play: String?, val declared: String?,
    val colors: List<Triple<String, Int?, String>>,
    val sections: List<Pair<String, List<String>>>,
)

private fun loadDetail(context: Context, model: AppModel, row: AppRow): AppDetail {
    val pm = context.packageManager
    val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
        PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA
    val pi: PackageInfo = pm.getPackageInfo(row.pkg, flags)
    val ai: ApplicationInfo = pi.applicationInfo!!

    val one = arrayOf(row.pkg)
    val lbl = arrayOf(row.label)
    val cat = ClassifierProvider.classify(context, one, lbl, true, Config.MODE_CATEGORY)
    val col = ClassifierProvider.classify(context, one, lbl, true, Config.MODE_COLOR)

    val play = ClassifierProvider.play(context)?.guess(row.pkg, row.label)?.let {
        PlayModel.CATEGORIES[it.category] + if (it.exact) " (정확)" else " (모델 ${(it.confidence * 100).toInt()}%)"
    }
    val declared = when (ai.category) {
        ApplicationInfo.CATEGORY_GAME -> "게임"
        ApplicationInfo.CATEGORY_AUDIO -> "오디오"
        ApplicationInfo.CATEGORY_VIDEO -> "비디오"
        ApplicationInfo.CATEGORY_IMAGE -> "이미지"
        ApplicationInfo.CATEGORY_SOCIAL -> "소셜"
        ApplicationInfo.CATEGORY_NEWS -> "뉴스"
        ApplicationInfo.CATEGORY_MAPS -> "지도"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "생산성"
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> "접근성"
        else -> null
    }

    val px = ColorEngine.pixels(pm.getApplicationIcon(row.pkg))
    val x = model.prefs.getInt(Config.KEY_COLOR_X, 50) / 100f
    val y = model.prefs.getInt(Config.KEY_COLOR_Y, 50) / 100f
    val colors = listOf(
        ColorEngine.Method.CENTROID to "중심점", ColorEngine.Method.AVERAGE to "평균", ColorEngine.Method.MODE to "최빈값",
        ColorEngine.Method.POSITION to "위치",
    ).map { (m, name) ->
        val c = ColorEngine.representative(px, m, x, y)
        Triple(name, c, c?.let { ColorEngine.hueName(it) } ?: "-")
    } + Triple("색 개수", null, ColorEngine.classify(px, ColorEngine.Method.SPREAD, x, y) ?: "-")

    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    val installer = runCatching { pm.getInstallSourceInfo(row.pkg).installingPackageName }.getOrNull()
    val meta = ai.metaData?.keySet()?.sorted()?.map { k -> "$k = ${ai.metaData.get(k)}" } ?: emptyList()
    return AppDetail(
        version = "${pi.versionName} (${pi.longVersionCode})",
        targetSdk = ai.targetSdkVersion, minSdk = ai.minSdkVersion,
        installed = fmt.format(Date(pi.firstInstallTime)),
        installer = installer?.let { "설치 출처: $it · 업데이트 ${fmt.format(Date(pi.lastUpdateTime))}" },
        system = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0,
        category = cat.getStringArray("targets")?.get(0), categoryReason = cat.getStringArray("reasons")?.get(0),
        colorGroup = col.getStringArray("targets")?.get(0), colorReason = col.getStringArray("reasons")?.get(0),
        play = play, declared = declared,
        colors = colors,
        sections = listOf(
            "권한" to (pi.requestedPermissions?.toList() ?: emptyList()),
            "액티비티" to (pi.activities?.map { it.name } ?: emptyList()),
            "서비스" to (pi.services?.map { it.name } ?: emptyList()),
            "리시버" to (pi.receivers?.map { it.name } ?: emptyList()),
            "프로바이더" to (pi.providers?.map { "${it.name} (${it.authority})" } ?: emptyList()),
            "메타데이터" to meta,
        ),
    )
}

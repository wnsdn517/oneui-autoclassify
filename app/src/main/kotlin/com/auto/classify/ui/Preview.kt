package com.auto.classify.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import top.yukonga.miuix.kmp.basic.TextField
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.auto.classify.core.Directives
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

private val iconCache = LruCache<String, ImageBitmap>(300)

/** App icon loaded off the main thread and cached. */
@Composable
fun AppIcon(pkg: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Reading the version subscribes to overrides; the key carries this app's icon stamp, so only a changed icon reloads.
    AppOverrides.version
    val key = "${pkg ?: ""}#${AppOverrides.iconStamp(pkg ?: "")}"
    val bitmap by produceState(iconCache.get(key), key) {
        if (value == null && pkg != null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val b = AppOverrides.customIcon(context, pkg) ?: run {
                        val d = context.packageManager.getApplicationIcon(pkg)
                        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
                            d.setBounds(0, 0, 96, 96)
                            d.draw(Canvas(it))
                        }
                    }
                    b.asImageBitmap().also { iconCache.put(key, it) }
                }.getOrNull()
            }
        }
    }
    val b = bitmap
    val shaped = when (IconShape.mode) {
        Directives.CIRCLE -> modifier.clip(CircleShape)
        Directives.SQUARE -> modifier.clip(RoundedCornerShape(12.dp))
        else -> modifier
    }
    if (b != null) Image(b, null, shaped) else Box(modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)))
}

/** Icon shape from the !아이콘모양 directive ("" = as-is). AppModel keeps it in step with the rules. */
object IconShape {
    var mode by mutableStateOf("")
}

/**
 * Apps screen pages as the launcher would draw them; folders open on tap. With [onMove], apps
 * can be long-pressed and dragged onto a folder (or anywhere else = out of folders), or tapped
 * to pick where they go. folder = null means out of folders.
 */
@Composable
fun LayoutPages(
    layout: JSONObject?, modifier: Modifier = Modifier,
    onMove: ((pkg: String, folder: String?) -> Unit)? = null,
    maxGridHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity,
    stacked: Boolean = false,
    labels: Boolean = true,
) {
    if (layout == null) return
    val scope = rememberCoroutineScope()
    val cols = layout.optInt("cols", 4).coerceAtLeast(1)
    val rows = layout.optInt("rows", 6).coerceAtLeast(1)
    val pages = layout.optJSONArray("pages") ?: JSONArray()
    val pager = rememberPagerState { pages.length().coerceAtLeast(1) }
    val pageRatio = screenRatio()
    val wallpaper = rememberWallpaper()
    var openFolder by remember { mutableStateOf<JSONObject?>(null) }
    var picking by remember { mutableStateOf<JSONObject?>(null) }
    // Drag: the app being dragged, pointer in root coordinates, and the visible cells' bounds.
    var dragged by remember { mutableStateOf<JSONObject?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val cells = remember { mutableStateMapOf<String, Pair<Rect, JSONObject>>() }
    val hovered = dragged?.let { d ->
        cells.values.firstOrNull { (r, item) -> item !== d && r.contains(pointer) }?.second
    }
    val folderNames = remember(layout) {
        val out = LinkedHashSet<String>()
        for (p in 0 until pages.length()) {
            val items = pages.optJSONArray(p) ?: continue
            for (i in 0 until items.length()) {
                val f = items.optJSONObject(i) ?: continue
                if (f.optString("type") == "folder" && f.optString("title").isNotEmpty()) out.add(f.optString("title"))
            }
        }
        out.toList()
    }

    Box(modifier.onGloballyPositioned { origin = it.positionInRoot() }) {
        Column {
            // Stacked: every page drawn one under another; otherwise swipe through them in a pager.
            val pageGrid: @Composable (Int) -> Unit = { p ->
                val items = pages.optJSONArray(p) ?: JSONArray()
                val ratio = pageRatio
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val w = if (maxGridHeight == androidx.compose.ui.unit.Dp.Infinity) maxWidth else minOf(maxWidth, maxGridHeight * ratio)
                // Icons shrink with the cells, so a small backup grid does not let neighbours touch.
                val icon = minOf(44.dp, w / cols * 0.6f)
                androidx.compose.foundation.layout.BoxWithConstraints(
                    Modifier.width(w).aspectRatio(ratio).clip(RoundedCornerShape(20.dp)).launcherBackdrop(wallpaper)
                ) {
                    SearchPill(maxHeight)
                Column(Modifier.gridArea(maxHeight)) {
                    for (r in 0 until rows) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            for (c in 0 until cols) {
                                val it = items.optJSONObject(r * cols + c)
                                val key = "$p:${r * cols + c}"
                                Box(
                                    Modifier.weight(1f).fillMaxSize().onGloballyPositioned { lc ->
                                        if (it != null && p == pager.currentPage) cells[key] = lc.boundsInRoot() to it
                                        else cells.remove(key)
                                    },
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    if (it != null) {
                                        val isApp = it.optString("type") != "folder"
                                        val drag = if (onMove != null && isApp) Modifier.pointerInput(it) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { o ->
                                                    dragged = it
                                                    pointer = (cells[key]?.first?.topLeft ?: Offset.Zero) + o
                                                },
                                                onDrag = { ch, d -> ch.consume(); pointer += d },
                                                onDragEnd = {
                                                    val d = dragged
                                                    val target = d?.let { x ->
                                                        cells.values.firstOrNull { (rc, item) -> item !== x && rc.contains(pointer) }?.second
                                                    }
                                                    dragged = null
                                                    if (d != null) {
                                                        val folder = target?.takeIf { t -> t.optString("type") == "folder" }?.optString("title")
                                                        onMove(d.optString("pkg"), folder)
                                                    }
                                                },
                                                onDragCancel = { dragged = null },
                                            )
                                        } else Modifier
                                        Cell(
                                            it, drag.alpha(if (dragged === it) 0.3f else 1f),
                                            iconSize = icon, highlight = hovered === it && !isApp,
                                            labels = labels,
                                        ) { if (!isApp) openFolder = it else if (onMove != null) picking = it }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                }
            }
            if (stacked) {
                // Each page keeps its grid width, so the pages sit side by side and scroll sideways.
                val pageWidth = if (maxGridHeight == androidx.compose.ui.unit.Dp.Infinity) 260.dp else maxGridHeight * pageRatio
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (p in 0 until pages.length()) Box(Modifier.width(pageWidth)) { pageGrid(p) }
                }
            } else {
                HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 12.dp, userScrollEnabled = dragged == null) { p -> pageGrid(p) }
                PageDots(pages.length().coerceAtLeast(1), pager.currentPage) { i -> scope.launch { pager.animateScrollToPage(i) } }
            }
        }
        dragged?.let { d ->
            val half = with(LocalDensity.current) { 26.dp.roundToPx() }
            AppIcon(
                d.optString("pkg"),
                Modifier.offset { IntOffset((pointer.x - origin.x).toInt() - half, (pointer.y - origin.y).toInt() - half * 2) }
                    .size(52.dp),
            )
        }
    }
    openFolder?.let { f ->
        FolderDialog(
            f, layout.optInt("folderCols", 3).coerceAtLeast(1), layout.optInt("folderRows", 4).coerceAtLeast(1), labels,
            onApp = if (onMove != null) { a -> openFolder = null; picking = a } else null,
        ) { openFolder = null }
    }
    picking?.let { a ->
        MoveDialog(a.optString("label"), folderNames, onPick = { folder ->
            picking = null
            onMove?.invoke(a.optString("pkg"), folder)
        }) { picking = null }
    }
}

/** Where should this app go: out of folders, an existing folder, or a new one. */
@Composable
fun MoveDialog(label: String, folders: List<String>, onPick: (String?) -> Unit, onClose: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    WindowDialog(show = true, title = label, onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            ArrowPreference(title = "폴더 밖으로", onClick = { onPick(null) })
            for (f in folders) ArrowPreference(title = f, onClick = { onPick(f) })
            TextField(
                value = newName, onValueChange = { newName = it }, useLabelAsPlaceholder = true,
                label = "새 폴더 이름", singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            SheetActions("취소", onClose, "새 폴더로", { if (newName.isNotBlank()) onPick(newName.trim()) })
        }
    }
}

@Composable
private fun Cell(
    item: JSONObject, modifier: Modifier = Modifier, iconSize: Dp = 44.dp, highlight: Boolean = false,
    labels: Boolean = true, onTap: () -> Unit,
) {
    val isFolder = item.optString("type") == "folder"
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            .then(if (highlight) Modifier.background(MiuixTheme.colorScheme.primary.copy(alpha = 0.25f)) else Modifier)
            .clickable(onClick = onTap).padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isFolder) {
            val apps = item.optJSONArray("apps") ?: JSONArray()
            LauncherFolderTile(
                iconSize, if (item.has("color")) item.optInt("color") else null,
                (0 until apps.length()).mapNotNull { apps.optJSONObject(it)?.optString("pkg") },
            )
        } else {
            AppIcon(item.optString("pkg"), Modifier.size(iconSize))
        }
        if (labels) Text(
            if (isFolder) item.optString("title") else item.optString("label"),
            Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
            color = androidx.compose.ui.graphics.Color.White,
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FolderDialog(
    folder: JSONObject, cols: Int, rows: Int, labels: Boolean,
    onApp: ((JSONObject) -> Unit)? = null, onClose: () -> Unit,
) {
    val apps = folder.optJSONArray("apps") ?: JSONArray()
    val cap = cols * rows
    val pages = ((apps.length() + cap - 1) / cap).coerceAtLeast(1)
    val pager = rememberPagerState { pages }
    val scope = rememberCoroutineScope()
    WindowDialog(show = true, title = folder.optString("title").ifEmpty { "(이름 없음)" },
        summary = "앱 ${apps.length()}개",
        onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth()) {
            // Same paging as the home pages: swipe sideways, dots below.
            HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 12.dp) { p ->
                Column(Modifier.fillMaxWidth()) {
                    for (r in 0 until rows) {
                        Row(Modifier.fillMaxWidth()) {
                            for (c in 0 until cols) {
                                val a = apps.optJSONObject(p * cap + r * cols + c)
                                Column(
                                    Modifier.weight(1f).height(82.dp).clip(RoundedCornerShape(12.dp))
                                        .then(if (a != null && onApp != null) Modifier.clickable { onApp(a) } else Modifier)
                                        .padding(top = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    if (a != null) {
                                        AppIcon(a.optString("pkg"), Modifier.size(48.dp))
                                        if (labels) Text(
                                            a.optString("label"), Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
                                            fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (pages > 1) PageDots(pages, pager.currentPage) { i -> scope.launch { pager.animateScrollToPage(i) } }
        }
    }
}

/** Organize preview: picture before/after, what moves where, and Apply at the bottom. */
@Composable
fun PreviewSheet(model: AppModel, plan: JSONObject) {
    val moves = plan.optJSONArray("moves") ?: JSONArray()
    val created = plan.optJSONArray("created") ?: JSONArray()
    val emptied = plan.optJSONArray("emptied") ?: JSONArray()
    val changed = plan.optBoolean("changed", moves.length() > 0)
    val after = plan.optJSONObject("after") ?: JSONObject()
    var editor by remember(plan) { mutableStateOf(EditorState.from(after)) }
    val close = { model.plan = null }

    // The grid gets what is left of the screen after the sheet's title, tabs and buttons.
    val gridMax = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.92f - 330).coerceAtLeast(280f).dp
    WindowBottomSheet(show = true, title = "정리 미리보기", onDismissRequest = close) {
        Column(Modifier.heightIn(max = 900.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                model.gridWarning?.let { warning ->
                    top.yukonga.miuix.kmp.basic.Card(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                            color = MiuixTheme.colorScheme.errorContainer,
                            contentColor = MiuixTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text("배치 경고", style = MiuixTheme.textStyles.title4, color = MiuixTheme.colorScheme.onErrorContainer)
                        Text(warning, Modifier.padding(top = 4.dp), style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onErrorContainer)
                    }
                }
                Hint(
                    if (!changed) "이미 정리되어 있어요"
                    else "앱 ${moves.length()}개 이동 · 새 폴더 ${created.length()}개"
                )
                LayoutEditor(editor, maxGridHeight = gridMax)
                if (com.auto.classify.ai.ApiLlm.fromPrefs(model.appContext) != null) {
                    if (model.aiRunning) {
                        WorkingText(model.aiStage, Modifier.padding(top = 8.dp))
                        AppearingChips(model.aiFolders)
                    } else {
                        AiInput(model, after)
                    }
                    model.aiError?.let { ErrorText(it) }
                    AiReply(model)
                }
                if (editor.dirty) {
                    Text(
                        "직접 바꾼 배치가 있어요 · 되돌리기",
                        Modifier.fillMaxWidth().clickable { editor = EditorState.from(after) }.padding(8.dp),
                        textAlign = TextAlign.Center, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.primary,
                    )
                }
                if (emptied.length() > 0) {
                    Hint("비게 되어 삭제: " + (0 until emptied.length()).joinToString(", ") { emptied.getString(it) })
                }
                if (moves.length() > 0) {
                    Text("이동 내역 ${moves.length()}개", Modifier.padding(top = 12.dp, bottom = 4.dp),
                        style = MiuixTheme.textStyles.title4)
                    for (i in 0 until moves.length()) {
                        val m = moves.getJSONObject(i)
                        val from = if (m.isNull("from")) "폴더 밖" else m.getString("from")
                        val to = if (m.getString("to") == "(폴더밖)") "폴더 밖으로" else m.getString("to")
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(m.optString("pkg"), Modifier.size(36.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(m.getString("label"), style = MiuixTheme.textStyles.body1)
                                Text("$from → $to", style = MiuixTheme.textStyles.body2,
                                    color = MiuixTheme.colorScheme.primary)
                                val reason = m.optString("reason")
                                if (reason.isNotEmpty()) Text(reason, style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
            if (changed || editor.dirty) {
                // Text typed for the AI but never sent: the first Apply only warns, the second applies.
                var warned by remember { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(model.aiDraft) { warned = false }
                SheetActions("취소", close, "적용", {
                    if (model.aiDraft.isNotBlank() && !warned) {
                        warned = true
                        model.toast("AI에게 입력한 내용이 아직 전송되지 않았어요. 한 번 더 누르면 그대로 적용돼요")
                    } else {
                        model.plan = null
                        if (editor.dirty) model.applyLayout(EditorState.from(after), editor) else model.request("apply")
                    }
                })
            } else {
                SheetActions("닫기", close, "확인", close)
            }
        }
    }
}

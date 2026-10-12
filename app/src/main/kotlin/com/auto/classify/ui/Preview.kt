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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
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
    val bitmap by produceState(iconCache.get(pkg ?: ""), pkg) {
        if (value == null && pkg != null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val d = context.packageManager.getApplicationIcon(pkg)
                    val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
                    d.setBounds(0, 0, 96, 96)
                    d.draw(Canvas(b))
                    b.asImageBitmap().also { iconCache.put(pkg, it) }
                }.getOrNull()
            }
        }
    }
    val b = bitmap
    if (b != null) Image(b, null, modifier) else Box(modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)))
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
) {
    if (layout == null) return
    val cols = layout.optInt("cols", 4).coerceAtLeast(1)
    val rows = layout.optInt("rows", 6).coerceAtLeast(1)
    val pages = layout.optJSONArray("pages") ?: JSONArray()
    val pager = rememberPagerState { pages.length().coerceAtLeast(1) }
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
            HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 12.dp, userScrollEnabled = dragged == null) { p ->
                val items = pages.optJSONArray(p) ?: JSONArray()
                val ratio = cols / (rows * 1.22f)
                androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val w = if (maxGridHeight == androidx.compose.ui.unit.Dp.Infinity) maxWidth else minOf(maxWidth, maxGridHeight * ratio)
                Column(
                    Modifier.width(w).aspectRatio(ratio)
                        .clip(RoundedCornerShape(20.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh).padding(6.dp)
                ) {
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
                                            highlight = hovered === it && !isApp,
                                        ) { if (!isApp) openFolder = it else if (onMove != null) picking = it }
                                    }
                                }
                            }
                        }
                    }
                }
                }
            }
            Text(
                "${pager.currentPage + 1} / ${pages.length()}",
                Modifier.fillMaxWidth().padding(top = 6.dp), textAlign = TextAlign.Center,
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
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
        FolderDialog(f, onApp = if (onMove != null) { a -> openFolder = null; picking = a } else null) { openFolder = null }
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
private fun Cell(item: JSONObject, modifier: Modifier = Modifier, highlight: Boolean = false, onTap: () -> Unit) {
    val isFolder = item.optString("type") == "folder"
    Column(
        modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
            .then(if (highlight) Modifier.background(MiuixTheme.colorScheme.primary.copy(alpha = 0.25f)) else Modifier)
            .clickable(onClick = onTap).padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isFolder) {
            val apps = item.optJSONArray("apps") ?: JSONArray()
            val tint = if (item.has("color")) androidx.compose.ui.graphics.Color(item.optInt("color"))
                else MiuixTheme.colorScheme.surfaceContainerHighest
            Column(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(tint)
                    .padding(4.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
            ) {
                for (r in 0 until 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    for (c in 0 until 2) {
                        val a = apps.optJSONObject(r * 2 + c)
                        if (a != null) AppIcon(a.optString("pkg"), Modifier.size(16.dp)) else Spacer(Modifier.size(16.dp))
                    }
                }
            }
        } else {
            AppIcon(item.optString("pkg"), Modifier.size(44.dp))
        }
        Text(
            if (isFolder) item.optString("title") else item.optString("label"),
            Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FolderDialog(folder: JSONObject, onApp: ((JSONObject) -> Unit)? = null, onClose: () -> Unit) {
    val apps = folder.optJSONArray("apps") ?: JSONArray()
    WindowDialog(show = true, title = folder.optString("title").ifEmpty { "(이름 없음)" },
        summary = "앱 ${apps.length()}개",
        onDismissRequest = onClose) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            for (i in 0 until apps.length()) {
                val a = apps.getJSONObject(i)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .then(if (onApp != null) Modifier.clickable { onApp(a) } else Modifier).padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(a.optString("pkg"), Modifier.size(36.dp))
                    Text(a.optString("label"), Modifier.padding(start = 12.dp))
                }
            }
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
    var showAfter by remember { mutableStateOf(0) }
    val after = plan.optJSONObject("after") ?: JSONObject()
    var editor by remember(plan) { mutableStateOf(EditorState.from(after)) }
    var details by remember { mutableStateOf(false) }
    val close = { model.plan = null }

    // The grid gets what is left of the screen after the sheet's title, tabs and buttons.
    val gridMax = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.92f - 330).coerceAtLeast(280f).dp
    WindowBottomSheet(show = true, title = "정리 미리보기", onDismissRequest = close) {
        Column(Modifier.heightIn(max = 900.dp)) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Hint(
                    if (!changed) "이미 정리되어 있어요"
                    else "앱 ${moves.length()}개 이동 · 새 폴더 ${created.length()}개"
                )
                SegTabs(listOf("변경 후", "변경 전"), showAfter, { showAfter = it }, Modifier.padding(vertical = 8.dp))
                if (showAfter == 0) {
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
                } else {
                    LayoutPages(plan.optJSONObject("before"), maxGridHeight = gridMax)
                }
                if (emptied.length() > 0) {
                    Hint("비게 되어 삭제: " + (0 until emptied.length()).joinToString(", ") { emptied.getString(it) })
                }
                if (moves.length() > 0) {
                    ArrowPreference(
                        title = "이동 내역 ${moves.length()}개",
                        summary = if (details) "접기" else "펼쳐서 보기",
                        onClick = { details = !details },
                    )
                    if (details) {
                        for (i in 0 until moves.length()) {
                            val m = moves.getJSONObject(i)
                            val from = if (m.isNull("from")) "폴더 밖" else m.getString("from")
                            val to = if (m.getString("to") == "(폴더밖)") "폴더 밖으로" else m.getString("to")
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                AppIcon(m.optString("pkg"), Modifier.size(32.dp))
                                Column(Modifier.padding(start = 10.dp)) {
                                    Text("${m.getString("label")}  ·  $from → $to", style = MiuixTheme.textStyles.body2)
                                    val reason = m.optString("reason")
                                    if (reason.isNotEmpty()) Text(reason, style = MiuixTheme.textStyles.footnote1,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                }
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

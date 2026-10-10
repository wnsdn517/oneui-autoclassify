package com.auto.classify.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateIntOffsetAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ---- model --------------------------------------------------------------------------------------

class EApp(val id: Int, val pkg: String, val label: String)

class EItem(val key: String, val id: Int, val folder: Boolean, title: String, val app: EApp?, val color: Int?) {
    var title by mutableStateOf(title)
    val apps: SnapshotStateList<EApp> = mutableStateListOf()
}

/** The apps screen as an editable list of pages; [toJson] is what "applylayout" writes. */
class EditorState(val cols: Int, val rows: Int) {
    val pages = mutableStateListOf<SnapshotStateList<EItem>>()
    var dirty by mutableStateOf(false)
    private var newIds = 0
    val capacity get() = cols * rows

    fun newFolder(title: String) = EItem("new${++newIds}", -newIds, true, title, null, null)

    /** Pushes overflow to the next page (like the launcher) and drops empty pages. */
    fun normalize() {
        var i = 0
        while (i < pages.size) {
            val p = pages[i]
            while (p.size > capacity) {
                if (i + 1 >= pages.size) pages.add(mutableStateListOf())
                pages[i + 1].add(0, p.removeAt(p.size - 1))
            }
            i++
        }
        pages.removeAll { it.isEmpty() }
        if (pages.isEmpty()) pages.add(mutableStateListOf())
    }

    fun locate(item: EItem): Pair<Int, Int>? {
        for ((p, l) in pages.withIndex()) {
            val i = l.indexOf(item)
            if (i >= 0) return p to i
        }
        return null
    }

    fun toJson(): JSONObject {
        val ps = JSONArray()
        for (page in pages) {
            val items = JSONArray()
            for (it in page) {
                if (it.folder) {
                    val apps = JSONArray()
                    it.apps.forEach { a -> apps.put(JSONObject().put("id", a.id).put("pkg", a.pkg).put("label", a.label)) }
                    items.put(JSONObject().put("type", "folder").put("id", it.id).put("title", it.title).put("apps", apps)
                        .apply { if (it.color != null) put("color", it.color) })
                } else {
                    val a = it.app!!
                    items.put(JSONObject().put("type", "app").put("id", a.id).put("pkg", a.pkg).put("label", a.label))
                }
            }
            ps.put(items)
        }
        return JSONObject().put("cols", cols).put("rows", rows).put("pages", ps)
    }

    /** Folder of each app (title, or null when loose), for turning hand moves into rules. */
    fun folderOf(): Map<String, String?> {
        val out = HashMap<String, String?>()
        for (page in pages) for (it in page) {
            if (it.folder) it.apps.forEach { a -> out[a.pkg] = it.title } else out[it.app!!.pkg] = null
        }
        return out
    }

    companion object {
        fun from(layout: JSONObject): EditorState {
            val s = EditorState(layout.optInt("cols", 4).coerceAtLeast(1), layout.optInt("rows", 6).coerceAtLeast(1))
            val ps = layout.optJSONArray("pages") ?: JSONArray()
            for (p in 0 until ps.length()) {
                val items = ps.optJSONArray(p) ?: continue
                val page = mutableStateListOf<EItem>()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    if (o.optString("type") == "folder") {
                        val f = EItem("f${o.optInt("id")}", o.optInt("id", -1), true, o.optString("title"), null,
                            if (o.has("color")) o.optInt("color") else null)
                        val apps = o.optJSONArray("apps") ?: JSONArray()
                        for (k in 0 until apps.length()) {
                            val a = apps.getJSONObject(k)
                            f.apps.add(EApp(a.optInt("id", -1), a.optString("pkg"), a.optString("label")))
                        }
                        page.add(f)
                    } else {
                        val a = EApp(o.optInt("id", -1), o.optString("pkg"), o.optString("label"))
                        page.add(EItem("a${a.id}:${a.pkg}", a.id, false, a.label, a, null))
                    }
                }
                s.pages.add(page)
            }
            if (s.pages.isEmpty()) s.pages.add(mutableStateListOf())
            return s
        }
    }
}

// ---- editor -------------------------------------------------------------------------------------

private const val FOLDER_COLS = 4

/**
 * Launcher-like editing: long-press an icon and drag; others make room, holding at the left or
 * right edge turns the page, holding over an app or folder drops into it (two apps make a new
 * folder). Tap a folder to open it in place; its apps can be reordered or dragged out.
 */
@Composable
fun LayoutEditor(state: EditorState, modifier: Modifier = Modifier, maxGridHeight: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp.Infinity) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState { state.pages.size }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var gridRect by remember { mutableStateOf(Rect.Zero) }
    var panelRect by remember { mutableStateOf(Rect.Zero) }
    var panelGridRect by remember { mutableStateOf(Rect.Zero) }
    var titleRect by remember { mutableStateOf(Rect.Zero) }
    var openFolder by remember { mutableStateOf<EItem?>(null) }
    var openFrom by remember { mutableStateOf(Rect.Zero) }

    // Drag state
    var dragItem by remember { mutableStateOf<EItem?>(null) }
    var dragApp by remember { mutableStateOf<EApp?>(null) } // set while dragging inside an open folder
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var mergeTarget by remember { mutableStateOf<EItem?>(null) }
    var mergeSince by remember { mutableStateOf(0L) }
    var mergeReady by remember { mutableStateOf(false) }
    var edgeSince by remember { mutableStateOf(0L) }
    var pendingIdx by remember { mutableStateOf(-1) }
    var pendingSince by remember { mutableStateOf(0L) }
    val edge = with(density) { 28.dp.toPx() }
    // The floating icon sits above the finger so it stays visible; what it is over is what counts.
    val lift = with(density) { 36.dp.toPx() }
    fun hit() = Offset(pointer.x, pointer.y - lift)

    fun cellOf(p: Offset, rect: Rect, cols: Int, rows: Int): Int {
        val cw = rect.width / cols
        val ch = rect.height / rows
        val c = ((p.x - rect.left) / cw).toInt().coerceIn(0, cols - 1)
        val r = ((p.y - rect.top) / ch).toInt().coerceIn(0, rows - 1)
        return r * cols + c
    }

    /** Over the icon part of a cell (folders: almost the whole cell), not the gap beside it. */
    fun overIcon(p: Offset, rect: Rect, cols: Int, rows: Int, folder: Boolean): Boolean {
        val cw = rect.width / cols
        val ch = rect.height / rows
        val fx = ((p.x - rect.left) / cw) % 1f
        val fy = ((p.y - rect.top) / ch) % 1f
        return if (folder) fx in 0.08f..0.92f else fx in 0.18f..0.82f && fy in 0.0f..0.75f
    }

    fun currentPage() = state.pages.getOrNull(pager.currentPage)

    /** Page-level move while dragging (not inside an open folder). */
    fun onPageDrag() {
        val item = dragItem ?: return
        val page = currentPage() ?: return
        val h = hit()
        if (!gridRect.contains(h)) return
        val idx = cellOf(h, gridRect, state.cols, state.rows)
        val over = page.getOrNull(idx)
        // Apps go into apps or folders; a folder only merges into another folder.
        if (over != null && over !== item && (!item.folder || over.folder) && overIcon(h, gridRect, state.cols, state.rows, over.folder)) {
            if (mergeTarget !== over) {
                mergeTarget = over
                mergeSince = System.currentTimeMillis()
                mergeReady = false
            }
            return
        }
        mergeTarget = null
        mergeReady = false
        // Make room only after a short hover, like the launcher; moving straight across
        // icons (on the way to a folder) must not shuffle them.
        if (pendingIdx != idx) {
            pendingIdx = idx
            pendingSince = System.currentTimeMillis()
        }
    }

    fun applyReorder() {
        val item = dragItem ?: return
        val page = currentPage() ?: return
        val idx = pendingIdx
        if (idx < 0) return
        val from = page.indexOf(item)
        val to = idx.coerceAtMost(if (from >= 0) page.size - 1 else page.size)
        if (from == to) return
        if (from >= 0) page.removeAt(from) else state.locate(item)?.let { (p, i) -> state.pages[p].removeAt(i) }
        page.add(to.coerceAtMost(page.size), item)
        state.normalize()
        state.dirty = true
    }

    // Timers: page turning at the edges and the hover delay before dropping into an icon.
    LaunchedEffect(dragItem, dragApp) {
        while (dragItem != null || dragApp != null) {
            delay(80)
            val now = System.currentTimeMillis()
            if (mergeTarget != null && !mergeReady && now - mergeSince > 300) mergeReady = true
            if (mergeTarget == null && pendingIdx >= 0 && now - pendingSince > 220) {
                applyReorder()
                pendingIdx = -1
            }
            val item = dragItem ?: continue
            val atLeft = pointer.x < gridRect.left + edge
            val atRight = pointer.x > gridRect.right - edge
            if (!(atLeft || atRight) || pager.isScrollInProgress) {
                edgeSince = 0
                continue
            }
            if (edgeSince == 0L) edgeSince = now
            if (now - edgeSince < 550) continue
            edgeSince = now
            val cur = pager.currentPage
            val target = if (atLeft) cur - 1 else cur + 1
            if (target < 0) continue
            if (target >= state.pages.size) {
                if ((currentPage()?.size ?: 0) <= 1) continue
                state.pages.add(mutableStateListOf())
            }
            state.locate(item)?.let { (p, i) -> state.pages[p].removeAt(i) }
            state.pages[target].add(item)
            state.normalize()
            state.dirty = true
            mergeTarget = null
            scope.launch { pager.animateScrollToPage(state.locate(item)?.first ?: target) }
        }
    }

    fun endDrag() {
        val item = dragItem
        val target = mergeTarget
        if (item != null && target != null && mergeReady && item.folder && target.folder) {
            state.locate(item)?.let { (p, i) -> state.pages[p].removeAt(i) }
            target.apps.addAll(item.apps)
            item.apps.clear()
            state.dirty = true
        } else if (item != null && target != null && mergeReady && item.app != null) {
            state.locate(item)?.let { (p, i) -> state.pages[p].removeAt(i) }
            if (target.folder) {
                target.apps.add(item.app)
            } else {
                val loc = state.locate(target)
                if (loc != null) {
                    val f = state.newFolder("새 폴더")
                    f.apps.add(target.app!!)
                    f.apps.add(item.app)
                    state.pages[loc.first][loc.second] = f
                }
            }
            state.dirty = true
        }
        state.normalize()
        // Dropped over a spot without waiting: still move there.
        if (item != null && mergeTarget == null && pendingIdx >= 0) applyReorder()
        pendingIdx = -1
        dragItem = null
        dragApp = null
        mergeTarget = null
        mergeReady = false
    }

    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val originAtDown = origin
                    var moved = false
                    var released = false
                    // A long press means the finger stays still on the screen. When the sheet
                    // scrolls, the editor moves along with the finger, so the finger's local
                    // position barely changes: compare screen positions (local + editor origin).
                    // (Consumption can't be used: the sheet itself consumes every down.)
                    val downScreen = down.position + originAtDown
                    val slop = viewConfiguration.touchSlop
                    val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val ev = awaitPointerEvent(PointerEventPass.Final)
                            val c = ev.changes.firstOrNull { it.id == down.id }
                            if (c == null || !c.pressed) {
                                released = true
                                break
                            }
                            if ((c.position + origin - downScreen).getDistance() > slop ||
                                (c.position - down.position).getDistance() > slop ||
                                (origin - originAtDown).getDistance() > slop
                            ) {
                                moved = true
                                break
                            }
                        }
                    } == null
                    if ((origin - originAtDown).getDistance() > viewConfiguration.touchSlop) moved = true
                    val p = down.position
                    if (released && !moved) {
                        // Tap: open a folder, or close the open one when tapping outside it.
                        val f = openFolder
                        if (f != null) {
                            if (!panelRect.contains(p)) openFolder = null
                        } else if (gridRect.contains(p)) {
                            val page = currentPage()
                            val it = page?.getOrNull(cellOf(p, gridRect, state.cols, state.rows))
                            if (it != null && it.folder) {
                                val cw = gridRect.width / state.cols
                                val ch = gridRect.height / state.rows
                                val i = page.indexOf(it)
                                openFrom = Rect(
                                    gridRect.left + (i % state.cols) * cw, gridRect.top + (i / state.cols) * ch,
                                    gridRect.left + (i % state.cols + 1) * cw, gridRect.top + (i / state.cols + 1) * ch,
                                )
                                openFolder = it
                            }
                        }
                        return@awaitEachGesture
                    }
                    if (!timedOut || moved) return@awaitEachGesture

                    // Long press: pick up what is under the finger.
                    val f = openFolder
                    if (f != null) {
                        if (!panelGridRect.contains(p) || titleRect.contains(p)) return@awaitEachGesture
                        val rowsInPanel = ((f.apps.size + FOLDER_COLS - 1) / FOLDER_COLS).coerceAtLeast(1)
                        dragApp = f.apps.getOrNull(cellOf(p, panelGridRect, FOLDER_COLS, rowsInPanel)) ?: return@awaitEachGesture
                    } else {
                        if (!gridRect.contains(p)) return@awaitEachGesture
                        dragItem = currentPage()?.getOrNull(cellOf(p, gridRect, state.cols, state.rows)) ?: return@awaitEachGesture
                    }
                    pointer = p
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        c.consume()
                        if (!c.pressed) break
                        pointer = c.position
                        val app = dragApp
                        val folder = openFolder
                        if (app != null && folder != null) {
                            if (panelRect.contains(hit())) {
                                val rowsInPanel = ((folder.apps.size + FOLDER_COLS - 1) / FOLDER_COLS).coerceAtLeast(1)
                                val to = cellOf(hit(), panelGridRect, FOLDER_COLS, rowsInPanel).coerceAtMost(folder.apps.size - 1)
                                val from = folder.apps.indexOf(app)
                                if (from >= 0 && from != to) {
                                    folder.apps.removeAt(from)
                                    folder.apps.add(to, app)
                                    state.dirty = true
                                }
                            } else {
                                // Out of the folder: it closes and the app continues on the page.
                                folder.apps.remove(app)
                                openFolder = null
                                val item = EItem("a${app.id}:${app.pkg}", app.id, false, app.label, app, null)
                                val page = currentPage() ?: break
                                if (folder.apps.isEmpty()) state.locate(folder)?.let { (pp, i) -> state.pages[pp].removeAt(i) }
                                page.add(item)
                                state.normalize()
                                dragApp = null
                                dragItem = item
                                state.dirty = true
                            }
                        } else {
                            onPageDrag()
                        }
                    }
                    endDrag()
                }
            }
    ) {
        Column {
            HorizontalPager(pager, Modifier.fillMaxWidth(), pageSpacing = 12.dp,
                userScrollEnabled = dragItem == null && dragApp == null && openFolder == null) { p ->
                val items = state.pages.getOrNull(p) ?: return@HorizontalPager
                val ratio = state.cols / (state.rows * 1.22f)
                // As big as fits: the whole page stays visible in the sheet without scrolling.
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val w = if (maxGridHeight == androidx.compose.ui.unit.Dp.Infinity) maxWidth else minOf(maxWidth, maxGridHeight * ratio)
                BoxWithConstraints(
                    Modifier.width(w).aspectRatio(ratio)
                        .clip(RoundedCornerShape(20.dp)).background(MiuixTheme.colorScheme.surfaceContainerHigh).padding(6.dp)
                        .onGloballyPositioned { if (p == pager.currentPage) gridRect = it.boundsInRoot().translate(-origin) }
                ) {
                    val cw = maxWidth / state.cols
                    val ch = maxHeight / state.rows
                    items.forEachIndexed { i, it ->
                        key(it.key) {
                            val target = with(density) { IntOffset((cw * (i % state.cols)).roundToPx(), (ch * (i / state.cols)).roundToPx()) }
                            val pos by animateIntOffsetAsState(target, spring(stiffness = 600f), label = "cell")
                            Box(
                                Modifier.offset { pos }.width(cw).height(ch)
                                    .alpha(if (it === dragItem) 0f else 1f),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                EditorCell(it, highlight = it === mergeTarget && mergeReady)
                            }
                        }
                    }
                }
                }
            }
            PageDots(state.pages.size, pager.currentPage) { i -> scope.launch { pager.animateScrollToPage(i) } }
        }

        // Folder opened in place (One UI style): grows from the folder icon.
        val f = openFolder
        val grow by animateFloatAsState(if (f != null) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 500f), label = "folder")
        if (f != null || grow > 0.01f) {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f * grow)))
        }
        if (f != null) {
            FolderPanel(
                f, dragApp, grow, openFrom, panelRect,
                Modifier.align(Alignment.Center).fillMaxWidth(0.9f)
                    .onGloballyPositioned { panelRect = it.boundsInRoot().translate(-origin) },
                onGrid = { panelGridRect = it.translate(-origin) },
                onTitle = { titleRect = it.translate(-origin) },
                onRename = { t -> f.title = t; state.dirty = true },
                onUngroup = {
                    // The folder goes away and its apps take its place on the page.
                    state.locate(f)?.let { (pg, i) ->
                        state.pages[pg].removeAt(i)
                        state.pages[pg].addAll(i, f.apps.map { a -> EItem("a${a.id}:${a.pkg}", a.id, false, a.label, a, null) })
                    }
                    f.apps.clear()
                    openFolder = null
                    state.normalize()
                    state.dirty = true
                },
            )
        }

        // The icon under the finger.
        val floating = dragApp ?: dragItem?.app
        val floatingFolder = dragItem?.takeIf { it.folder }
        if (floating != null || floatingFolder != null) {
            val half = with(density) { 30.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((pointer.x - half).toInt(), (pointer.y - lift - half).toInt()) }
                    .size(60.dp).graphicsLayer { scaleX = 1.12f; scaleY = 1.12f; alpha = 0.95f },
            ) {
                if (floatingFolder != null) FolderIcon(floatingFolder, Modifier.size(52.dp))
                else AppIcon(floating!!.pkg, Modifier.size(52.dp))
            }
        }
    }
}

/** One UI style page dots: tap one, or slide along them, to change page. */
@Composable
private fun PageDots(count: Int, current: Int, onSelect: (Int) -> Unit) {
    val density = LocalDensity.current
    val step = with(density) { 18.dp.toPx() }
    val width = 18.dp * count
    fun indexAt(x: Float) = (x / step).toInt().coerceIn(0, count - 1)
    Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.width(width + 24.dp).height(32.dp).clip(RoundedCornerShape(50))
                .padding(horizontal = 12.dp)
                .pointerInput(count) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        var last = indexAt(down.position.x)
                        onSelect(last)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            val i = indexAt(c.position.x)
                            if (i != last) {
                                last = i
                                onSelect(i)
                            }
                        }
                    }
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(count) { i ->
                Box(Modifier.width(18.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(if (i == current) 9.dp else 7.dp).clip(RoundedCornerShape(50))
                            .background(
                                if (i == current) MiuixTheme.colorScheme.onSurface
                                else MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.4f)
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorCell(item: EItem, highlight: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val scale by animateFloatAsState(if (highlight) 1.15f else 1f, label = "hover")
        Box(
            Modifier.size(46.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                .then(if (highlight) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, RoundedCornerShape(14.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (item.folder) FolderIcon(item, Modifier.size(44.dp)) else AppIcon(item.app!!.pkg, Modifier.size(44.dp))
        }
        Text(
            item.title,
            Modifier.padding(top = 2.dp, start = 2.dp, end = 2.dp),
            fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FolderIcon(folder: EItem, modifier: Modifier) {
    val tint = folder.color?.let { Color(it) } ?: MiuixTheme.colorScheme.surfaceContainerHighest
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(tint).padding(4.dp),
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        for (r in 0 until 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (c in 0 until 2) {
                val a = folder.apps.getOrNull(r * 2 + c)
                if (a != null) AppIcon(a.pkg, Modifier.size(16.dp)) else Spacer(Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun FolderPanel(
    folder: EItem, dragging: EApp?, grow: Float, from: Rect, panel: Rect, modifier: Modifier,
    onGrid: (Rect) -> Unit, onTitle: (Rect) -> Unit, onRename: (String) -> Unit, onUngroup: () -> Unit,
) {
    // Grow out of the folder icon's position.
    val ox = if (panel.width > 0) ((from.center.x - panel.left) / panel.width).coerceIn(0f, 1f) else 0.5f
    val oy = if (panel.height > 0) ((from.center.y - panel.top) / panel.height).coerceIn(0f, 1f) else 0.5f
    Column(
        modifier
            .graphicsLayer {
                transformOrigin = TransformOrigin(ox, oy)
                val s = 0.25f + 0.75f * grow
                scaleX = s
                scaleY = s
                alpha = grow
            }
            .clip(RoundedCornerShape(28.dp)).background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicTextField(
            value = folder.title,
            onValueChange = onRename,
            singleLine = true,
            textStyle = MiuixTheme.textStyles.title3.copy(color = MiuixTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)
                .onGloballyPositioned { onTitle(it.boundsInRoot()) },
        )
        val rows = ((folder.apps.size + FOLDER_COLS - 1) / FOLDER_COLS).coerceAtLeast(1)
        Column(Modifier.fillMaxWidth().onGloballyPositioned { onGrid(it.boundsInRoot()) }) {
            for (r in 0 until rows) {
                Row(Modifier.fillMaxWidth()) {
                    for (c in 0 until FOLDER_COLS) {
                        val a = folder.apps.getOrNull(r * FOLDER_COLS + c)
                        Column(
                            Modifier.weight(1f).height(82.dp).alpha(if (a != null && a === dragging) 0f else 1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (a != null) {
                                AppIcon(a.pkg, Modifier.padding(top = 4.dp).size(48.dp))
                                Text(a.label, Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp), fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
        Text(
            "폴더 풀기",
            Modifier.padding(top = 10.dp).clip(RoundedCornerShape(50)).clickable(onClick = onUngroup)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.body2,
        )
    }
}

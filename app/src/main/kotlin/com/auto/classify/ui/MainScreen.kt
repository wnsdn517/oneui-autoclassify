package com.auto.classify.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.isSystemInDarkTheme
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.auto.classify.core.Config
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingToolbarDefaults
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import androidx.compose.ui.graphics.RectangleShape
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.UploadCloud
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val TITLES = listOf("정리", "규칙", "UI 튜닝", "백업", "설정")

/** Height of the floating bar and the action above it; lists add it to their bottom padding. */
val LocalNavInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** Height of the title bar; lists add it to their top padding so the first row starts below it. */
val LocalTopInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

@Composable
fun MainScreen(model: AppModel, activity: MainActivity) {
    val pager = androidx.compose.foundation.pager.rememberPagerState { TITLES.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tab = pager.targetPage
    val go: (Int) -> Unit = { i -> scope.launch { pager.animateScrollToPage(i) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { model.messages.collect { activity.showMessage(it) } }
    LaunchedEffect(Unit) { Updater.check(context, quiet = true) }
    // Miuix blur, the way the Miuix sample app wires it: the pages are captured as a backdrop, the title
    // bar gets a progressive fade-out blur, and the floating bar gets a rounded texture blur.
    // The page background is captured as the backdrop, so the blur starts from the same color the pages show.
    // Dark mode is pure black; light mode follows the theme so cards and text stay readable.
    val dark = ThemeChoice.mode == "dark" || (ThemeChoice.mode != "light" && isSystemInDarkTheme())
    // Light page uses the theme background; the floating bar and capsule tint with the card surface.
    val background = if (dark) Color.Black else MiuixTheme.colorScheme.background
    // Status and navigation bar icons follow the app's theme, not the system's, so a forced theme stays readable.
    LaunchedEffect(dark) {
        val bars = androidx.core.view.WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        bars.isAppearanceLightStatusBars = !dark
        bars.isAppearanceLightNavigationBars = !dark
    }
    val backdrop = rememberLayerBackdrop {
        drawRect(background)
        drawContent()
    }
    var overlayHeight by remember { mutableIntStateOf(0) }
    var topHeight by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    Box(Modifier.fillMaxSize()) {
        // The pages run under the title bar and the floating bar so both have something to blur; each
        // list keeps its first and last rows clear of them through LocalTopInset and LocalNavInset.
        CompositionLocalProvider(
            // Lists start below the blur's fade-out (56dp past the bar), so the first rows never sit inside it.
            LocalTopInset provides with(density) { topHeight.toDp() } + 56.dp,
            LocalNavInset provides with(density) { overlayHeight.toDp() },
        ) {
            Box(Modifier.fillMaxSize().background(background).layerBackdrop(backdrop)) {
                // Swipe sideways between tabs, like HyperOS apps.
                androidx.compose.foundation.pager.HorizontalPager(pager) { page ->
                    when (page) {
                        0 -> OrganizeTab(model, openSettings = { go(4) })
                        1 -> RulesTab(model)
                        2 -> AppsTab(model)
                        3 -> BackupTab(model)
                        else -> SettingsTab(model, activity)
                    }
                }
            }
        }
        // Title bar: transparent over a progressive blur that fades out toward the content.
        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
            // Softer and taller than the title bar: the fade runs 36dp past it. topHeight is the bar alone.
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .height(with(density) { topHeight.toDp() } + 56.dp).progressiveTextureBlur(
                    backdrop = backdrop,
                    shape = RectangleShape,
                    gradient = ProgressiveBlur.Top.copy(curve = 1.0f),
                    blurRadius = 16f,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(BlendColorEntry(color = background.copy(alpha = 0.5f))),
                    ),
                ),
            )
            Column(
                Modifier.statusBarsPadding().offset(y = (-14).dp)
                    .onSizeChanged { topHeight = it.height },
            ) {
                TopAppBar(
                    title = if (tab == 0) "앱 자동 분류" else TITLES[tab],
                    color = Color.Transparent,
                )
            }
        }
        BoxWithConstraints(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .onSizeChanged { overlayHeight = it.height }
                .navigationBarsPadding()
                .padding(horizontal = 8.dp)
                .offset(y = 12.dp),
        ) {
            // The reserved width grows and shrinks with the capsule, so bar plus capsule stay centred as one group.
            // Nothing is clipped: the capsule sits beside the bar, and the bar moves to make room for it.
            val actionWidth by animateDpAsState(if (tab == 0 || tab == 1 || tab == 3) ACTION_SLOT else 0.dp, label = "actionSlot")
            val barWidth = (maxWidth - actionWidth).coerceAtMost(BAR_MAX_WIDTH)
            val barLeft = (maxWidth - barWidth - actionWidth) / 2
            // Tabs on a rounded, blurred surface over the pages: the Miuix sample's floating bar, copied as is.
            val floatingBarColor = Color.Transparent
            val floatingBarShape = RoundedCornerShape(FloatingToolbarDefaults.CornerRadius)
            val floatingHighlight = remember(dark) {
                if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight
            }
            Box(Modifier.align(Alignment.CenterStart).offset(x = barLeft).width(barWidth)) {
                FloatingNavigationBar(
                    modifier = Modifier.height(BAR_HEIGHT).textureBlur(
                        backdrop = backdrop,
                        shape = floatingBarShape,
                        blurRadius = 25f,
                        colors = BlurDefaults.blurColors(
                            blendColors = listOf(
                                BlendColorEntry(color = MiuixTheme.colorScheme.surfaceContainer.copy(0.6f)),
                            ),
                        ),
                        highlight = floatingHighlight,
                    ),
                    color = floatingBarColor,
                ) {
                    FloatingNavigationBarItem(tab == 0, { go(0) }, MiuixIcons.Home, TITLES[0])
                    FloatingNavigationBarItem(tab == 1, { go(1) }, MiuixIcons.Tune, TITLES[1])
                    FloatingNavigationBarItem(tab == 2, { go(2) }, MiuixIcons.GridView, TITLES[2])
                    FloatingNavigationBarItem(tab == 3, { go(3) }, MiuixIcons.Backup, TITLES[3])
                    FloatingNavigationBarItem(tab == 4, { go(4) }, MiuixIcons.Settings, TITLES[4])
                }
            }
            // The tab's main action is an icon capsule just right of the bar, the same height as the bar.
            Box(Modifier.align(Alignment.CenterStart).offset(x = barLeft + barWidth)) {
                BottomAction(model, tab, backdrop, background, dark)
            }
        }
    }
    DiagnosisHost(model, activity)
    UpdateDialog()
    model.plan?.let { PreviewSheet(model, it) }
}

/** Blur tint for the bars: pure black in dark mode, the card surface in light mode so bars match the cards. */
@Composable
private fun barTint(dark: Boolean, background: Color): Color =
    if (dark) background else MiuixTheme.colorScheme.surfaceContainer

/** Tab bar height. The action capsule matches it, so the two read as one row. */
private val BAR_HEIGHT = 56.dp

/** Width reserved on each side of the tab bar, so the bar stays centred on every tab. */
private val ACTION_SLOT = BAR_HEIGHT + 12.dp

/** Widest the tab bar gets on large screens; the bar then sits centred with its capsule. */
private val BAR_MAX_WIDTH = 360.dp

private class ActionPill(val label: String, val icon: ImageVector, val enabled: Boolean, val onClick: () -> Unit)

/**
 * The tab's main action as an icon capsule at the right end of the bar. The slot is reserved on every tab,
 * so the tab bar keeps its width; the capsule only fades and scales in or out.
 */
@Composable
private fun BottomAction(
    model: AppModel, tab: Int, backdrop: top.yukonga.miuix.kmp.blur.LayerBackdrop,
    background: Color, dark: Boolean,
) {
    model.settingsVersion
    val pill: ActionPill? = when (tab) {
        0 -> {
            val off = model.disabledFeatures().contains(Diagnosis.F_ORGANIZE)
            ActionPill(
                label = when {
                    off -> "꺼짐"
                    model.busy -> "요청 중"
                    else -> "미리보기"
                },
                icon = MiuixIcons.Show,
                enabled = !off && !model.busy,
            ) { model.request("plan") }
        }
        1 -> ActionPill("규칙 추가", MiuixIcons.Add, true) { model.ruleEditor = -1 to RuleItem() }
        3 -> ActionPill("백업", MiuixIcons.Backup, !model.disabledFeatures().contains(Diagnosis.F_BACKUP)) { model.requestBackupNow() }
        else -> null
    }
    // Keep the last action while it fades out, so the capsule does not blank before it leaves.
    var last by remember { mutableStateOf<ActionPill?>(null) }
    if (pill != null) last = pill
    // Measured on device: the bar's visible pill sits about 20dp above this box's centre, so lift the action to match.
    Box(Modifier.width(ACTION_SLOT).height(BAR_HEIGHT).offset(y = (-20).dp), contentAlignment = Alignment.CenterEnd) {
        androidx.compose.animation.AnimatedVisibility(
            visible = pill != null,
            // The capsule grows out of the bar's right edge, with a 12dp gap at rest, so it reads as split off the bar.
            // Scaling from the left edge keeps it inside the slot, so the slot clip never cuts it.
            enter = androidx.compose.animation.scaleIn(
                initialScale = 0.6f,
                // Grows from its own centre, so during the spring it never reaches back over the bar's blur.
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center,
                animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 300f),
            ) + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.scaleOut(
                targetScale = 0.6f,
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin.Center,
            ) + androidx.compose.animation.fadeOut(),
        ) {
            val p = last ?: return@AnimatedVisibility
            // Same blurred look as the floating bar beside it.
            Box(
                Modifier.size(BAR_HEIGHT).textureBlur(
                    backdrop = backdrop,
                    shape = CircleShape,
                    blurRadius = 25f,
                    colors = BlurDefaults.blurColors(
                        blendColors = listOf(BlendColorEntry(color = barTint(dark, background).copy(alpha = if (dark) 0.4f else 0.6f))),
                    ),
                    highlight = if (dark) Highlight.GlassStrokeMiddleDark else Highlight.GlassStrokeMiddleLight,
                ).clip(CircleShape)
                    // Light mode: the blur is near white on a white page, so a faint outline keeps the pill visible.
                    .then(if (dark) Modifier else Modifier.border(1.dp, Color.Black.copy(alpha = 0.08f), CircleShape))
                    .clickable(enabled = p.enabled, onClick = p.onClick),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = p.icon,
                    contentDescription = p.label,
                    modifier = Modifier.size(24.dp),
                    tint = if (p.enabled) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
    }
}

/** Unified status banner: shows error > module-off > update, in that priority. Hidden when clean. */
@Composable
fun RiskBanner(model: AppModel, onClick: () -> Unit) {
    model.settingsVersion // re-read on settings changes
    val d = model.diagnosis
    val off = model.disabledFeatures()
    val acked = d != null && d.level != Diagnosis.Level.OK &&
        d.signature() == model.prefs.getString(Config.KEY_ACKED_HEALTH, "")
    val hasError = off.isNotEmpty() || acked
    val moduleOff = !model.moduleActive
    val latest = Updater.latest
    val hasUpdate = latest != null && Updater.newer(latest.version, Updater.current(androidx.compose.ui.platform.LocalContext.current))
    // Priority: error > module-off > update. Nothing shown when all clean.
    if (!hasError && !moduleOff && !hasUpdate) return
    when {
        hasError -> Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 12.dp),
            insideMargin = androidx.compose.foundation.layout.PaddingValues(16.dp),
            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(
                color = MiuixTheme.colorScheme.errorContainer,
                contentColor = MiuixTheme.colorScheme.onErrorContainer,
            ),
            onClick = onClick,
        ) {
            Text("충돌 또는 예기치 않은 문제가 발생할 수 있어요", style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onErrorContainer)
            Text(
                if (off.isEmpty()) "눌러서 진단 보기"
                else "꺼진 기능: " + off.joinToString(", ") { Diagnosis.featureName(it) } + " · 눌러서 진단 보기",
                style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onErrorContainer,
            )
        }
        moduleOff -> Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 12.dp),
            insideMargin = androidx.compose.foundation.layout.PaddingValues(16.dp),
            onClick = onClick,
        ) {
            Text("모듈 비활성", style = MiuixTheme.textStyles.title4)
            Text("LSPosed에서 켜 주세요 · 눌러서 진단 보기", style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        hasUpdate -> Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 6.dp, bottom = 12.dp),
            insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            onClick = { Updater.dialogOpen = true },
        ) {
            Text("새 버전 ${latest!!.version} 이 나왔어요 · 눌러서 보기", style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.primary)
        }
    }
}

/** Shows the self-check dialog when there is something new to report, or when asked. */
@Composable
fun DiagnosisHost(model: AppModel, activity: MainActivity) {
    model.settingsVersion
    val d = model.diagnosis ?: return
    val context = LocalContext.current
    var dismissedSignature by remember { mutableStateOf<String?>(null) }
    val acked = d.signature() == model.prefs.getString(Config.KEY_ACKED_HEALTH, "")
    val show = d.level == Diagnosis.Level.FATAL ||
        (d.level != Diagnosis.Level.OK && !acked && dismissedSignature != d.signature()) ||
        model.showDiagnosis
    if (!show) return
    val title = when (d.level) {
        Diagnosis.Level.FATAL -> "사용할 수 없음"
        Diagnosis.Level.ERROR -> "일부 기능에 문제가 있음"
        Diagnosis.Level.WARN -> "일부 기능에 문제가 있을 수 있음"
        else -> "문제 없음"
    }
    val close = {
        dismissedSignature = d.signature()
        model.showDiagnosis = false
    }
    WindowDialog(
        show = true,
        title = title,
        onDismissRequest = if (d.level == Diagnosis.Level.FATAL) null else close,
    ) {
        // Summary and buttons share one scroll so a long message list stays reachable on small screens.
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text("One UI 홈 ${d.launcherVersion}\n\n" + d.messages.joinToString("\n") { "• $it" },
                style = MiuixTheme.textStyles.body2)
            Spacer(Modifier.height(12.dp))
            // Loads a new hook build. Home ends its own process (no root needed); if Home doesn't answer,
            // root restarts it, or Home's app info opens for a manual force stop.
            TextButton("One UI 홈 재시작", {
                model.restartHome {
                    Thread {
                        model.messages.tryEmit(HomeRestart.restart(context))
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ model.requestHealth() }, 5000)
                    }.start()
                }
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            TextButton("로그 내보내기", { activity.exportLogs() }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (Updater.hasUpdate(context)) {
                    TextButton("업데이트", { Updater.install(context) }, Modifier.weight(1f))
                }
                when (d.level) {
                    Diagnosis.Level.FATAL -> TextButton("종료", { activity.finishAndRemoveTask() }, Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary())
                    Diagnosis.Level.ERROR -> TextButton("무시 (해당 기능 끄기)", {
                        val off = model.disabledFeatures().apply { addAll(d.brokenFeatures) }
                        model.edit({
                            putString(Config.KEY_DISABLED, off.joinToString(","))
                            putString(Config.KEY_ACKED_HEALTH, d.signature())
                        })
                        close()
                        model.requestHealth()
                    }, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                    else -> TextButton("닫기", {
                        if (d.level != Diagnosis.Level.OK) model.edit({ putString(Config.KEY_ACKED_HEALTH, d.signature()) }, reload = false)
                        close()
                    }, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
                }
            }
            if (model.showDiagnosis && model.disabledFeatures().isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TextButton("꺼진 기능 다시 켜기", {
                    model.edit({ remove(Config.KEY_DISABLED); remove(Config.KEY_ACKED_HEALTH) })
                    close()
                    model.requestHealth()
                }, Modifier.fillMaxWidth())
            }
        }
    }
}

/** Small colored label used for categories and states. */
@Composable
fun Tag(text: String, color: Color = MiuixTheme.colorScheme.secondaryContainer) {
    Box(
        Modifier.padding(end = 6.dp, top = 4.dp)
            .then(Modifier)
    ) {
        Card(
            cornerRadius = 8.dp,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 3.dp),
            colors = top.yukonga.miuix.kmp.basic.CardDefaults.defaultColors(color = color),
        ) {
            Text(text, style = MiuixTheme.textStyles.footnote1)
        }
    }
}

/** The update window: what's new, then install now or later. Opens when a newer release is found. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val latest = Updater.latest ?: return
    if (!Updater.newer(latest.version, Updater.current(context))) return
    if (!Updater.dialogOpen) return
    WindowDialog(
        show = true,
        title = "새 버전 ${latest.version}",
        onDismissRequest = { Updater.dialogOpen = false },
    ) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
        Text(latest.notes.take(600), style = MiuixTheme.textStyles.body2)
        Updater.progress?.let { pr ->
            Text("받는 중 ${(pr * 100).toInt()}%", Modifier.padding(top = 8.dp), style = MiuixTheme.textStyles.footnote1)
        }
        Updater.status?.let { Text(it, Modifier.padding(top = 8.dp), style = MiuixTheme.textStyles.body2) }
        SheetActions("나중에", { Updater.dialogOpen = false }, "지금 설치", { Updater.install(context) },
            primaryEnabled = Updater.progress == null)
        }
    }
}

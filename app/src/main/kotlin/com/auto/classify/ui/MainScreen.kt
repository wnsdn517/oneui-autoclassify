package com.auto.classify.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import com.auto.classify.core.Config
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val TITLES = listOf("정리", "앱", "백업", "설정")

/** Height of the floating bar and the action above it; lists add it to their bottom padding. */
val LocalNavInset = androidx.compose.runtime.compositionLocalOf { 0.dp }

@Composable
fun MainScreen(model: AppModel, activity: MainActivity) {
    val pager = androidx.compose.foundation.pager.rememberPagerState { TITLES.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val tab = pager.targetPage
    val go: (Int) -> Unit = { i -> scope.launch { pager.animateScrollToPage(i) } }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { model.messages.collect { activity.showMessage(it) } }
    LaunchedEffect(Unit) { Updater.check(context, quiet = true) }
    val scroll = MiuixScrollBehavior()

    val hazeState = rememberHazeState()
    // Blur plus a light tint of the theme surface, so the bar reads as a surface over the list.
    val surfaceTint = MiuixTheme.colorScheme.surface.copy(alpha = 0.55f)
    val barStyle = HazeBlurStyle {
        blurRadius(24.dp)
        backgroundColor(surfaceTint)
    }
    var overlayHeight by remember { mutableIntStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    Scaffold(
        topBar = { TopAppBar(title = if (tab == 0) "앱 자동 분류" else TITLES[tab], scrollBehavior = scroll) },
    ) { padding ->
        // The pages run under the floating bar so the blur has something to blur; each list keeps its
        // last rows clear of the bar through LocalNavInset.
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()).nestedScroll(scroll.nestedScrollConnection)) {
            CompositionLocalProvider(LocalNavInset provides with(density) { overlayHeight.toDp() }) {
                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    // Swipe sideways between tabs, like HyperOS apps.
                    androidx.compose.foundation.pager.HorizontalPager(pager, beyondViewportPageCount = 1) { page ->
                        when (page) {
                            0 -> OrganizeTab(model) { go(3) }
                            1 -> AppsTab(model)
                            2 -> BackupTab(model)
                            else -> SettingsTab(model, activity)
                        }
                    }
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .onSizeChanged { overlayHeight = it.height }
                    .navigationBarsPadding(),
            ) {
                // The tab's main action sits right above the tabs, within thumb reach, on the same blur.
                Box(Modifier.fillMaxWidth().hazeBlur(input = HazeInput.Sources(hazeState), style = barStyle)) {
                    BottomAction(model, tab)
                }
                // Tabs on a blurred, tinted, rounded surface over the pages.
                Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(28.dp))) {
                    Box(
                        Modifier.matchParentSize().hazeBlur(
                            input = HazeInput.Sources(hazeState),
                            style = barStyle,
                        ),
                    )
                    FloatingNavigationBar(color = Color.Transparent) {
                        FloatingNavigationBarItem(tab == 0, { go(0) }, MiuixIcons.GridView, TITLES[0])
                        FloatingNavigationBarItem(tab == 1, { go(1) }, MiuixIcons.Layers, TITLES[1])
                        FloatingNavigationBarItem(tab == 2, { go(2) }, MiuixIcons.Backup, TITLES[2])
                        FloatingNavigationBarItem(tab == 3, { go(3) }, MiuixIcons.Settings, TITLES[3])
                    }
                }
            }
        }
    }
    DiagnosisHost(model, activity)
    UpdateDialog()
    model.plan?.let { PreviewSheet(model, it) }
}

@Composable
private fun BottomAction(model: AppModel, tab: Int) {
    model.settingsVersion
    val (text, enabled, action) = when (tab) {
        0 -> {
            val off = model.disabledFeatures().contains(Diagnosis.F_ORGANIZE)
            Triple(
                when {
                    off -> "정리 기능이 꺼져 있어요"
                    model.busy -> "One UI 홈에 요청하는 중…"
                    else -> "미리보기"
                },
                !off && !model.busy,
            ) { model.request("plan") }
        }
        2 -> Triple("지금 백업", !model.disabledFeatures().contains(Diagnosis.F_BACKUP)) {
            model.request("backup")
            model.requestBackups()
        }
        else -> return
    }
    TextButton(
        text = text, onClick = action, enabled = enabled,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(52.dp),
        colors = ButtonDefaults.textButtonColorsPrimary(),
    )
}

/** Red banner shown after the user dismissed a self-check problem. */
@Composable
fun RiskBanner(model: AppModel, onClick: () -> Unit) {
    model.settingsVersion // re-read on settings changes
    val d = model.diagnosis
    val off = model.disabledFeatures()
    val acked = d != null && d.level != Diagnosis.Level.OK &&
        d.signature() == model.prefs.getString(Config.KEY_ACKED_HEALTH, "")
    if (off.isEmpty() && !acked) return
    Card(
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
        summary = "One UI 홈 ${d.launcherVersion}\n\n" + d.messages.joinToString("\n") { "• $it" },
        onDismissRequest = if (d.level == Diagnosis.Level.FATAL) null else close,
    ) {
        Column {
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

/** miuix-style message: inverse colors so it stands out in light and dark mode. */
@Composable
fun MessageBubble(text: String?) {
    var last by remember { mutableStateOf("") }
    if (text != null) last = text
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.animation.AnimatedVisibility(
            visible = text != null,
            enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
            exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 },
        ) {
            Text(
                last,
                Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.onSurface)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                color = MiuixTheme.colorScheme.surface,
                style = MiuixTheme.textStyles.body2,
            )
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
        summary = latest.notes.take(600),
        onDismissRequest = { Updater.dialogOpen = false },
    ) {
        Updater.progress?.let { pr ->
            Text("받는 중 ${(pr * 100).toInt()}%", Modifier.padding(top = 8.dp), style = MiuixTheme.textStyles.footnote1)
        }
        Updater.status?.let { Text(it, Modifier.padding(top = 8.dp), style = MiuixTheme.textStyles.body2) }
        SheetActions("나중에", { Updater.dialogOpen = false }, "지금 설치", { Updater.install(context) },
            primaryEnabled = Updater.progress == null)
    }
}

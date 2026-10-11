package com.auto.classify.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Entry point: HyperOS-style (miuix) Compose UI. */
class MainActivity : ComponentActivity() {

    lateinit var model: AppModel
        private set
    private var pendingLog: String? = null

    private val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingLog ?: return@registerForActivityResult
        pendingLog = null
        if (uri == null) return@registerForActivityResult
        runCatching { contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) } }
            .onSuccess { model.toast("로그를 저장했어요") }
            .onFailure { model.toast("로그 저장 실패: ${it.message}") }
    }

    private val saveSettings = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)!!.use { it.write(SettingsBackup.export(this).toByteArray()) }
        }.onSuccess { model.toast("설정을 내보냈어요 (API 키는 제외)") }
            .onFailure { model.toast("내보내기 실패: ${it.message}") }
    }

    private val loadSettings = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            model.importSettings(text)
        }.onSuccess { model.toast("설정 ${it}개를 불러왔어요") }
            .onFailure { model.toast("불러오기 실패: ${it.message}") }
    }

    fun exportSettings() =
        saveSettings.launch("autoclassify-settings-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".json")

    fun importSettings() = loadSettings.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The AI notification only shows with this; the AI job runs either way.
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        model = AppModel(this)
        ThemeChoice.mode = model.prefs.getString(ThemeChoice.KEY, "system")!!
        setContent { AppTheme { MainScreen(model, this) } }
    }

    override fun onStart() {
        super.onStart()
        model.start()
    }

    override fun onStop() {
        model.stop()
        super.onStop()
    }

    private var messageText by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var messageView: androidx.compose.ui.platform.ComposeView? = null
    private val hideMessage = Runnable { messageText = null }
    private val removeMessage = Runnable {
        messageView?.let { v -> runCatching { if (v.isAttachedToWindow) windowManager.removeViewImmediate(v) } }
    }

    /**
     * The in-app message, in its own small window added last: sheets and dialogs are windows
     * too, and a message inside the activity would sit under them.
     */
    fun showMessage(text: String) {
        val token = window.decorView.windowToken ?: return
        val view = messageView ?: androidx.compose.ui.platform.ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@MainActivity)
            setViewTreeSavedStateRegistryOwner(this@MainActivity)
            setContent { AppTheme { MessageBubble(messageText) } }
        }.also { messageView = it }
        val main = window.decorView
        main.removeCallbacks(hideMessage)
        main.removeCallbacks(removeMessage)
        // Re-add so it is above any sheet or dialog opened since.
        runCatching { if (view.isAttachedToWindow) windowManager.removeViewImmediate(view) }
        val lp = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = android.view.Gravity.BOTTOM
            y = (110 * resources.displayMetrics.density).toInt()
            this.token = token
        }
        if (runCatching { windowManager.addView(view, lp) }.isFailure) {
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show()
            return
        }
        messageText = text
        main.postDelayed(hideMessage, 3200)
        main.postDelayed(removeMessage, 3700)
    }

    override fun onDestroy() {
        messageView?.let { v -> runCatching { if (v.isAttachedToWindow) windowManager.removeViewImmediate(v) } }
        super.onDestroy()
    }

    /** Launcher log + diagnosis + device info, saved through the system file picker. */
    fun exportLogs() {
        model.requestLogs { launcherLog ->
            Thread {
                val text = launcherLog ?: rootRead(
                    "cat /data/data/com.sec.android.app.launcher/files/autoclassify.log.1 " +
                        "/data/data/com.sec.android.app.launcher/files/autoclassify.log 2>/dev/null; echo; " +
                        "echo '--- logcat ---'; logcat -d -t 2000 -s AutoClassify:* VectorLegacyBridge:*"
                ) ?: "(One UI 홈 응답 없음, 루트로도 로그를 읽지 못함)"
                val d = model.diagnosis
                val header = buildString {
                    append("AutoClassify ").append(packageManager.getPackageInfo(packageName, 0).versionName).append('\n')
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    append(" · Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
                    append("모듈 활성: ").append(if (com.auto.classify.core.ModuleStatus.isHooked()) "예 (자체 연결)" else if (model.moduleActive) "홈 응답으로 확인" else "확인 안 됨").append('\n')
                    append("홈 설치: ").append(runCatching { packageManager.getPackageInfo(com.auto.classify.core.Config.LAUNCHER_PKG, 0).versionName }.getOrDefault("없음")).append('\n')
                    val off = model.disabledFeatures()
                    if (off.isNotEmpty()) append("꺼진 기능: ").append(off.joinToString(", ")).append('\n')
                    if (d != null) {
                        append("진단: ").append(d.level).append('\n')
                        d.messages.forEach { append("  ").append(it).append('\n') }
                    }
                    append("\n--- 설정 앱 기록 ---\n").append(model.eventTrail()).append('\n')
                    append("\n--- One UI 홈 기록 ---\n")
                }
                runOnUiThread {
                    pendingLog = header + text
                    saveLog.launch("autoclassify-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt")
                }
            }.start()
        }
    }

    private fun rootRead(cmd: String): String? = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        p.waitFor()
        out.ifBlank { null }
    }.getOrNull()
}

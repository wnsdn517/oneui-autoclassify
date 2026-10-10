package com.auto.classify.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Updates from the GitHub releases of this project: checks the latest release, downloads its
 * APK and installs it (silently with root, else through the system installer's confirmation).
 */
object Updater {

    const val REPO = "wnsdn517/oneui-autoclassify"
    val pageUrl = "https://github.com/$REPO/releases"

    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    var latest by mutableStateOf<Release?>(null)
        private set
    var checking by mutableStateOf(false)
        private set
    /** 0..1 while downloading, null otherwise. */
    var progress by mutableStateOf<Float?>(null)
        private set
    var status by mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun current(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** True when [a] is a newer version than [b] ("0.13.1" > "0.13.0"). */
    fun newer(a: String, b: String): Boolean {
        val x = a.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.trimStart('v').split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    fun hasUpdate(context: Context) = latest?.let { newer(it.version, current(context)) } == true

    fun check(context: Context, quiet: Boolean = false) {
        if (checking) return
        checking = true
        if (!quiet) status = null
        val app = context.applicationContext
        scope.launch {
            runCatching {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                if (c.responseCode == 404) error("아직 릴리즈가 없어요")
                if (c.responseCode >= 400) error("확인 실패 (${c.responseCode})")
                val o = JSONObject(c.inputStream.bufferedReader().readText())
                val assets = o.optJSONArray("assets")
                var url = ""
                var size = 0L
                for (i in 0 until (assets?.length() ?: 0)) {
                    val a = assets!!.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) {
                        url = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
                Release(o.optString("tag_name").trimStart('v'), o.optString("body"), url, size)
            }.onSuccess {
                latest = it
                if (!quiet) status = if (newer(it.version, current(app))) null else "최신 버전이에요 (${current(app)})"
            }.onFailure { if (!quiet) status = it.message ?: "확인 실패" }
            checking = false
        }
    }

    fun install(context: Context) {
        val r = latest ?: return
        if (progress != null || r.apkUrl.isEmpty()) return
        val app = context.applicationContext
        progress = 0f
        status = null
        scope.launch {
            runCatching {
                val file = File(app.cacheDir, "update.apk")
                val c = URL(r.apkUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(1 shl 16)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) progress = done.toFloat() / total
                        }
                    }
                }
                status = "설치하는 중…"
                if (!rootInstall(file)) sessionInstall(app, file)
            }.onFailure { status = "업데이트 실패: ${it.message}" }
            progress = null
        }
    }

    /** pm install through su; the app restarts as the new version. */
    private fun rootInstall(file: File): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "pm install -r -S ${file.length()}").redirectErrorStream(true).start()
        p.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor() == 0 && text.contains("Success")
    }.getOrDefault(false)

    /** System installer: Android shows its own "update this app?" confirmation. */
    private fun sessionInstall(context: Context, file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            s.commit(pending.intentSender)
        }
    }
}

/** Result of the system installer: asks the user to confirm, or reports a failure. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> Updater.status = "설치 실패: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없음")
        }
    }
}

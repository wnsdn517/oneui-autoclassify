package com.auto.classify.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Restarts One UI Home, so a new hook build loads. With root it force-stops Home, which the system
 * brings back at once; without root it opens Home's app info, where the user taps "강제 종료".
 */
object HomeRestart {
    const val HOME = "com.sec.android.app.launcher"

    /** Asks `su` once; true only when root is granted. Blocks, so call it off the main thread. */
    fun rootGranted(): Boolean = runCatching {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor() == 0 && text.contains("uid=0")
    }.getOrDefault(false)

    /** Blocks, so call it off the main thread. @return what happened, for the user. */
    fun restart(context: Context): String {
        if (rootGranted()) {
            val ok = runCatching {
                ProcessBuilder("su", "-c", "am force-stop $HOME").redirectErrorStream(true).start().waitFor() == 0
            }.getOrDefault(false)
            return if (ok) "One UI 홈을 다시 시작했어요" else "루트로 홈을 재시작하지 못했어요. 홈을 직접 재시작해 주세요"
        }
        // Opening app info from the main thread is fine; this only starts an activity.
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$HOME"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return "루트 권한이 없어서 One UI 홈 앱 정보를 열었어요. '강제 종료'를 누른 뒤 홈을 다시 열어 주세요"
    }
}

package com.auto.classify.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Keeps the process alive while an AI request runs, so closing or swiping away the app doesn't
 * cancel it. Jobs are counted; the service stops when the last one ends.
 */
class AiService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AI 작업", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("AI가 폴더를 정리하는 중")
            .setContentText("앱을 닫아도 계속 진행돼요")
            .setOngoing(true)
            .build()
        startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "ai_work"
        private const val ID = 4201
        private var jobs = 0

        @Synchronized
        fun begin(context: Context) {
            if (jobs++ == 0) {
                runCatching { context.startForegroundService(Intent(context, AiService::class.java)) }
            }
        }

        @Synchronized
        fun end(context: Context) {
            if (jobs > 0 && --jobs == 0) context.stopService(Intent(context, AiService::class.java))
        }
    }
}

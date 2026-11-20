package com.theohowie.heracles.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.theohowie.heracles.MainActivity

class AgentBuildService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(currentText), SERVICE_TYPE)
        running = true
        currentText = intent?.getStringExtra(EXTRA_TEXT) ?: "جارٍ التحضير للبناء…"
        updateNotification(currentText)
        return when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "مهام البناء",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "إبقاء الخدمة نشطة في الخلفية أثناء ترجمة أدوات البيئة المعزولة"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Heracles قيد البناء")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "heracles-build"
        private const val NOTIFICATION_ID = 0x4865
        private const val EXTRA_TEXT = "text"
        const val ACTION_STOP = "com.theohowie.heracles.STOP_BUILD"
        val SERVICE_TYPE: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        @Volatile
        private var running = false

        @Volatile
        private var currentText = "جارٍ التحضير للبناء…"

        fun isRunning(): Boolean = running

        fun start(context: Context, text: String = "جارٍ التحضير للبناء…") {
            currentText = text
            val i = Intent(context, AgentBuildService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching { context.startForegroundService(i) }
        }

        fun progress(context: Context, text: String) {
            currentText = text
            if (!running) return
            runCatching {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                val open = PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = Notification.Builder(context, CHANNEL_ID)
                    .setContentTitle("Heracles قيد البناء")
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
                nm.notify(NOTIFICATION_ID, n)
            }
        }

        fun stop(context: Context) {
            running = false
            runCatching { context.stopService(Intent(context, AgentBuildService::class.java)) }
        }
    }
}

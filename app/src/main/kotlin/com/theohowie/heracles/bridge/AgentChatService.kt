package com.theohowie.heracles.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.theohowie.heracles.MainActivity

class AgentChatService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT).orEmpty()
        startForeground(NOTIFICATION_ID, buildNotification(text))
        acquireWakeLock()
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        markProcessKilled()
        releaseWakeLock()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        releaseWakeLock()
        running = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        runCatching {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "heracles:chat-run",
            ).apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
        }
        wakeLock = null
    }

    private fun markProcessKilled() {
        runCatching {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_PROCESS_KILLED, true)
                .putLong(KEY_KILLED_AT, System.currentTimeMillis())
                .apply()
        }
    }

    private fun ensureChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "تشغيل المهام في الخلفية",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "إشعار دائم أثناء تنفيذ مهام الوكيل في الخلفية"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Heracles قيد تنفيذ المهمة")
            .setContentText(text.ifBlank { "جارٍ المعالجة…" })
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    data class KeepaliveStatus(
        val foregroundServiceRunning: Boolean,
        val batteryIgnored: Boolean,
        val batteryNote: String?,
        val autostartBrand: String,
        val autostartPath: String,
    )

    companion object {
        private const val CHANNEL_ID = "heracles_chat_running"
        private const val NOTIFICATION_ID = 2000
        private const val EXTRA_TEXT = "text"
        private const val WAKE_LOCK_TIMEOUT_MS = 2L * 60L * 60L * 1000L
        private const val PREFS = "heracles_chat_service"
        private const val KEY_PROCESS_KILLED = "process_killed"
        private const val KEY_KILLED_AT = "killed_at"

        @Volatile
        var running: Boolean = false
            private set

        fun start(context: Context) {
            try {
                runCatching {
                    context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .remove(KEY_PROCESS_KILLED)
                        .apply()
                }
                context.startForegroundService(
                    Intent(context, AgentChatService::class.java)
                        .putExtra(EXTRA_TEXT, "جارٍ معالجة طلبك…"),
                )
                running = true
            } catch (_: Exception) {
            }
        }

        fun update(context: Context, text: String) {
            if (!running) return
            runCatching {
                context.startService(
                    Intent(context, AgentChatService::class.java)
                        .putExtra(EXTRA_TEXT, text),
                )
            }
        }

        fun stop(context: Context) {
            running = false
            runCatching { context.stopService(Intent(context, AgentChatService::class.java)) }
        }

        fun processKilled(context: Context): Boolean = runCatching {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_PROCESS_KILLED, false)
        }.getOrDefault(false)

        fun processKilledAt(context: Context): Long = runCatching {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_KILLED_AT, 0L)
        }.getOrDefault(0L)

        fun consumeProcessKilled(context: Context) {
            runCatching {
                context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .remove(KEY_PROCESS_KILLED)
                    .apply()
            }
        }

        fun requestIgnoreBatteryOptimizations(context: Context): Boolean = runCatching {
            val pm = context.getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return true
            context.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrDefault(false)

        fun oemGuide(kind: String): Pair<String, String>? = when (kind) {
            "autostart" -> autostartGuideByBrand()
            "backgroundPopup" -> backgroundPopupGuide()
            "lockScreenShow" -> lockScreenShowGuide()
            else -> null
        }

        private fun brandKey(): String = (Build.BRAND + " " + Build.MANUFACTURER).lowercase()

        private fun isXiaomi(): Boolean =
            brandKey().contains("xiaomi") || brandKey().contains("redmi")

        private fun autostartGuideByBrand(): Pair<String, String> {
            val brand = brandKey()
            return when {
                brand.contains("xiaomi") || brand.contains("redmi") ->
                    "Xiaomi/Redmi" to "الإعدادات ← التطبيقات ← إدارة التطبيقات ← Heracles ← التشغيل التلقائي (سماح)"
                brand.contains("huawei") || brand.contains("honor") ->
                    "Huawei/Honor" to "الإعدادات ← التطبيقات ← تشغيل التطبيقات ← Heracles ← إدارة يدوية (تشغيل تلقائي)"
                brand.contains("oppo") || brand.contains("realme") ->
                    "OPPO/realme" to "الإعدادات ← البطارية ← إدارة طاقة التطبيقات ← Heracles ← السماح بالنشاط في الخلفية"
                brand.contains("vivo") || brand.contains("iqoo") ->
                    "vivo/iQOO" to "الإعدادات ← البطارية ← استهلاك الطاقة بالخلفية ← Heracles ← السماح بالاستهلاك العالي بالخلفية"
                brand.contains("samsung") ->
                    "Samsung" to "الإعدادات ← البطارية ← حدود استخدام الخلفية ← إضافة Heracles إلى «التطبيقات غير الموضوعة في السكون»"
                else -> "عام" to "الإعدادات ← البطارية ← Heracles ← السماح بالنشاط في الخلفية / غير مقيّد"
            }
        }

        private fun backgroundPopupGuide(): Pair<String, String> =
            if (isXiaomi()) {
                "Xiaomi/Redmi" to "الإعدادات ← التطبيقات ← Heracles ← أذونات أخرى ← النوافذ المنبثقة بالخلفية (سماح)"
            } else {
                "عام" to "يرجى تفعيل إذن النوافذ المنبثقة بالخلفية من إعدادات النظام ← التطبيقات ← Heracles"
            }

        private fun lockScreenShowGuide(): Pair<String, String> =
            if (isXiaomi()) {
                "Xiaomi/Redmi" to "الإعدادات ← التطبيقات ← Heracles ← أذونات أخرى ← العرض على شاشة القفل (سماح)"
            } else {
                "عام" to "يرجى تفعيل خيار العرض على شاشة القفل من إعدادات النظام ← التطبيقات ← Heracles"
            }

        fun keepaliveStatus(context: Context): KeepaliveStatus {
            var note: String? = null
            val fs = running
            val battery = runCatching {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            }.getOrElse {
                note = "تعذر قراءة حالة تحسين البطارية (${it.javaClass.simpleName})"
                false
            }
            if (battery && isXiaomi() && note == null) {
                note = "ملاحظة MIUI: خيار «بلا قيود» يختلف عن قائمة استثناء تحسين البطارية في النظام."
            }
            val (brand, path) = autostartGuideByBrand()
            return KeepaliveStatus(fs, battery, note, brand, path)
        }
    }
}

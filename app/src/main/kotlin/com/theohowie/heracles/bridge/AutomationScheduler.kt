package com.theohowie.heracles.bridge

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.theohowie.heracles.MainActivity
import org.json.JSONArray

object AutomationScheduler {
    private const val PREFS = "heracles_automation"
    private const val KEY_TASKS = "tasks"
    private const val KEY_PENDING = "pending"

    fun fireAction(context: Context): String = "${context.packageName}.AUTOMATION_FIRE"

    fun saveTasks(context: Context, tasksJson: String): Boolean = runCatching {
        cancelAll(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TASKS, tasksJson).commit()
        rescheduleAll(context)
        true
    }.getOrDefault(false)

    fun loadTasks(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TASKS, "[]") ?: "[]"

    fun consumePending(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        prefs.edit().remove(KEY_PENDING).apply()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
    }

    fun enqueuePending(context: Context, taskId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        val arr = runCatching { JSONArray(old) }.getOrElse { JSONArray() }
        arr.put(taskId)
        prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    fun rescheduleAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val tasks = runCatching { JSONArray(loadTasks(context)) }.getOrElse { JSONArray() }
        for (i in 0 until tasks.length()) {
            val t = tasks.optJSONObject(i) ?: continue
            if (!t.optBoolean("enabled", true)) continue
            val id = t.optString("id", "")
            if (id.isBlank()) continue
            val minutes = t.optInt("intervalMinutes", 0)
            if (t.optString("schedule", "interval") != "interval" || minutes <= 0) continue
            registerInterval(context, am, id, minutes)
        }
    }

    private fun registerInterval(
        context: Context,
        am: AlarmManager,
        id: String,
        minutes: Int,
    ) {
        val pi = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, AutomationReceiver::class.java)
                .putExtra("taskId", id)
                .setAction(fireAction(context)),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val intervalMs = minutes.toLong() * 60_000L
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + intervalMs,
            intervalMs,
            pi,
        )
    }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val tasks = runCatching { JSONArray(loadTasks(context)) }.getOrElse { JSONArray() }
        val action = fireAction(context)
        for (i in 0 until tasks.length()) {
            val id = tasks.optJSONObject(i)?.optString("id", "") ?: continue
            if (id.isBlank()) continue
            val pi = PendingIntent.getBroadcast(
                context,
                id.hashCode(),
                Intent(context, AutomationReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            am.cancel(pi)
        }
    }
}

class AutomationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutomationScheduler.fireAction(context)) return
        val taskId = intent.getStringExtra("taskId").orEmpty()
        if (taskId.isBlank()) return
        runCatching {
            AutomationScheduler.enqueuePending(context, taskId)
        }
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel("heracles_automation") == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        "heracles_automation",
                        "المهام المجدولة",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            val open = PendingIntent.getActivity(
                context,
                4,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(context, "heracles_automation")
                .setContentTitle("حان موعد المهمة المجدولة")
                .setContentText("افتح Heracles لتنفيذ المهمة ($taskId)")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(2003, n)
        }
    }
}

class AutomationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        runCatching { AutomationScheduler.rescheduleAll(context) }
    }
}

package com.babycry.analyzer.notify

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.WorkManager
import com.babycry.analyzer.MainActivity
import com.babycry.analyzer.R
import com.babycry.analyzer.data.CryRepository
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.trS
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Two gentle nudges a day to do tummy time (morning + afternoon), at parent-chosen hours. Each
 * adapts to the baby's age (how many sessions/day are recommended) and to how many the parent has
 * already logged today ("2 done, 3 to go").
 *
 * Uses **exact alarms** so each fires at the chosen hour even while the phone is idle. An exact
 * alarm is one-shot, so when a slot fires it re-arms itself for the same hour the next day.
 */
object TummyReminder {

    const val CHANNEL_ID = "tummy_reminders"
    const val NOTIFICATION_ID = 4203
    const val EXTRA_SLOT = "tummy_slot"
    const val SLOT_AM = "am"
    const val SLOT_PM = "pm"
    private const val REQ_AM = 4210
    private const val REQ_PM = 4211

    /**
     * Arm (or refresh) both daily reminders. [force] is kept for call-site compatibility; exact
     * scheduling is idempotent (re-computing "next 11:00" always yields the same instant), so we
     * simply (re)set both alarms every time.
     */
    fun schedule(context: Context, force: Boolean) {
        clearLegacyWork(context) // drop any periodic WorkManager reminder from older builds
        val repo = CryRepository.get(context)
        if (!repo.isTummyReminderEnabled()) {
            cancel(context)
            return
        }
        scheduleSlot(context, SLOT_AM, repo.tummyReminderHourAm())
        scheduleSlot(context, SLOT_PM, repo.tummyReminderHourPm())
    }

    private fun scheduleSlot(context: Context, slot: String, hour: Int) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = nextOccurrence(hour)
        val pi = alarmIntent(context, slot)
        val canExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }.onFailure {
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context, SLOT_AM))
        am.cancel(alarmIntent(context, SLOT_PM))
    }

    private fun alarmIntent(context: Context, slot: String): PendingIntent {
        val req = if (slot == SLOT_PM) REQ_PM else REQ_AM
        val intent = Intent(context, TummyAlarmReceiver::class.java).apply {
            putExtra(EXTRA_SLOT, slot)
        }
        return PendingIntent.getBroadcast(
            context,
            req,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun clearLegacyWork(context: Context) {
        runCatching {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("tummy_reminder_am")
            wm.cancelUniqueWork("tummy_reminder_pm")
            wm.cancelUniqueWork("tummy_reminder")
        }
    }

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    trS("Υπενθυμίσεις tummy time"),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = trS("Δύο ήπιες υπενθυμίσεις την ημέρα για tummy time, ανάλογα με την ηλικία.")
                },
            )
        }
    }

    /** Post the reminder for [slot] and re-arm it for tomorrow. Called from [TummyAlarmReceiver]. */
    suspend fun onFired(context: Context, slot: String) {
        val repo = CryRepository.get(context)
        if (!repo.isTummyReminderEnabled()) {
            cancel(context)
            return
        }
        // Re-arm the same slot for tomorrow first, so the daily cadence survives any error below.
        val hour = if (slot == SLOT_PM) repo.tummyReminderHourPm() else repo.tummyReminderHourAm()
        scheduleSlot(context, slot, hour)

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)

        val goal = repo.tummyDailyGoal()
        val done = repo.tummyDoneToday()
        val remaining = (goal - done).coerceAtLeast(0)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val body = body(goal, done, remaining)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title())
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun nextOccurrence(hour: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!next.after(now)) next.add(Calendar.DAY_OF_MONTH, 1)
        return next.timeInMillis
    }

    private fun title(): String = when (currentAppLang) {
        AppLang.EN -> "Tummy time \uD83E\uDD38"
        AppLang.EL -> "Ώρα για tummy time \uD83E\uDD38"
    }

    private fun body(goal: Int, done: Int, remaining: Int): String = when (currentAppLang) {
        AppLang.EN ->
            if (remaining > 0)
                "Good time for tummy time! Today's goal is about $goal sessions. You've done $done — $remaining to go. Always awake and supervised."
            else
                "Great job! You've hit today's tummy time goal ($done/$goal). Keep it up!"
        AppLang.EL ->
            if (remaining > 0)
                "Καλή ώρα για tummy time! Ο στόχος σήμερα είναι περίπου $goal φορές. Έχεις κάνει $done — απομένουν $remaining. Πάντα ξύπνιο και υπό επίβλεψη."
            else
                "Μπράβο! Πέτυχες τον στόχο tummy time για σήμερα ($done/$goal). Συνέχισε έτσι!"
    }
}

/** Receives a tummy-time alarm and posts the reminder off the main thread. */
class TummyAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getStringExtra(TummyReminder.EXTRA_SLOT) ?: TummyReminder.SLOT_AM
        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                TummyReminder.onFired(appContext, slot)
            } finally {
                result.finish()
            }
        }
    }
}

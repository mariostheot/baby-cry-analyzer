package com.babycry.analyzer.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.babycry.analyzer.MainActivity
import com.babycry.analyzer.R
import com.babycry.analyzer.data.CryRepository
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.trS
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Two gentle nudges a day to do tummy time (morning + afternoon), at parent-chosen hours. Each
 * adapts to the baby's age (how many sessions/day are recommended) and to how many the parent has
 * already logged today ("2 done, 3 to go"). A daily [PeriodicWorkRequest] per slot keeps them
 * going without the app being open; the exact hour is honoured via an initial delay.
 */
object TummyReminder {

    const val CHANNEL_ID = "tummy_reminders"
    const val NOTIFICATION_ID = 4203
    private const val WORK_AM = "tummy_reminder_am"
    private const val WORK_PM = "tummy_reminder_pm"

    /**
     * Arm (or refresh) both daily reminders. [force] = true (settings change) fully re-enqueues so
     * a new hour takes effect immediately; [force] = false (app start) keeps any existing schedule.
     */
    fun schedule(context: Context, force: Boolean) {
        val repo = CryRepository.get(context)
        if (!repo.isTummyReminderEnabled()) {
            cancel(context)
            return
        }
        enqueueSlot(context, WORK_AM, repo.tummyReminderHourAm(), force)
        enqueueSlot(context, WORK_PM, repo.tummyReminderHourPm(), force)
    }

    private fun enqueueSlot(context: Context, workName: String, hour: Int, force: Boolean) {
        val request = PeriodicWorkRequestBuilder<TummyReminderWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayToNextHour(hour), TimeUnit.MILLISECONDS)
            .build()
        val policy =
            if (force) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            else ExistingPeriodicWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(workName, policy, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_AM)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PM)
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

    private fun delayToNextHour(hour: Int): Long {
        val now = Calendar.getInstance()
        val next = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!next.after(now)) next.add(Calendar.DAY_OF_MONTH, 1)
        return next.timeInMillis - now.timeInMillis
    }
}

class TummyReminderWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = CryRepository.get(context)
        if (!repo.isTummyReminderEnabled()) return Result.success()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return Result.success()
        }
        TummyReminder.ensureChannel(context)

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
        val notification = NotificationCompat.Builder(context, TummyReminder.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title())
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(TummyReminder.NOTIFICATION_ID, notification)
        }
        return Result.success()
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

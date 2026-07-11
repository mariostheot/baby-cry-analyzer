package com.babycry.analyzer.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.babycry.analyzer.MainActivity
import com.babycry.analyzer.R
import com.babycry.analyzer.data.CryRepository
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.trS
import java.util.concurrent.TimeUnit

/**
 * A heads-up ~10 minutes before the baby's age-appropriate feeding time (counted from the last
 * logged feed). It doesn't claim the baby IS hungry - it just primes the parent: if a cry comes
 * around now, hunger is the most likely reason. Scheduled whenever a feed is logged (and on
 * app start), and re-checked when it fires so a newer feed cancels a stale reminder.
 */
object FeedReminder {

    const val CHANNEL_ID = "feed_reminders"
    const val NOTIFICATION_ID = 4202
    const val LEAD_MINUTES = 10L
    private const val WORK_NAME = "feed_reminder"
    private const val KEY_LAST_FEED = "last_feed_ts"

    /** Schedule the reminder [delayMs] from now, tied to the [lastFeedTs] it was computed from. */
    fun schedule(context: Context, delayMs: Long, lastFeedTs: Long) {
        val request = OneTimeWorkRequestBuilder<FeedReminderWorker>()
            .setInitialDelay(delayMs.coerceAtLeast(1_000L), TimeUnit.MILLISECONDS)
            .setInputData(Data.Builder().putLong(KEY_LAST_FEED, lastFeedTs).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    trS("Υπενθυμίσεις ταΐσματος"),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = trS("Σε ειδοποιεί λίγο πριν την ώρα που συνήθως πεινά το μωρό.")
                },
            )
        }
    }

    fun keyLastFeed(): String = KEY_LAST_FEED
}

class FeedReminderWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = CryRepository.get(context)
        val scheduledFor = inputData.getLong(FeedReminder.keyLastFeed(), 0L)
        val lastFeed = repo.lastFeedTimestamp()
        // A newer feed happened after we scheduled this (or the log was cleared): skip - a fresh
        // reminder was already queued for the new feed.
        if (lastFeed == null || lastFeed != scheduledFor) return Result.success()

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return Result.success()
        }
        FeedReminder.ensureChannel(context)

        val profile = repo.getProfile()
        val name = if (profile.hasName) profile.name else trS("το μωρό")

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val body = feedBody(name)
        val notification = NotificationCompat.Builder(context, FeedReminder.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(feedTitle())
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(FeedReminder.NOTIFICATION_ID, notification)
        }
        return Result.success()
    }

    private fun feedTitle(): String = when (currentAppLang) {
        AppLang.EN -> "Feeding time is near \uD83C\uDF7C"
        AppLang.EL -> "Πλησιάζει η ώρα ταΐσματος \uD83C\uDF7C"
    }

    private fun feedBody(name: String): String = when (currentAppLang) {
        AppLang.EN -> "In about ${FeedReminder.LEAD_MINUTES} minutes $name may get hungry. If a cry comes around now, hunger is the most likely reason."
        AppLang.EL -> "Σε περίπου ${FeedReminder.LEAD_MINUTES} λεπτά μπορεί να πεινάσει ο/η $name. Αν κλάψει τώρα, πιο πιθανή αιτία είναι η πείνα."
    }
}

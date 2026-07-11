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
import com.babycry.analyzer.MainActivity
import com.babycry.analyzer.R
import com.babycry.analyzer.data.CryRepository
import com.babycry.analyzer.ui.i18n.AppLang
import com.babycry.analyzer.ui.i18n.currentAppLang
import com.babycry.analyzer.ui.i18n.trS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * A heads-up ~10 minutes before the baby's age-appropriate feeding time (counted from the last
 * logged feed). It doesn't claim the baby IS hungry - it just primes the parent: if a cry comes
 * around now, hunger is the most likely reason. Scheduled whenever a feed is logged (and on
 * app start), and re-checked when it fires so a newer feed cancels a stale reminder.
 *
 * Uses an **exact alarm** (not WorkManager), so it fires on time even while the phone is idle -
 * a "feeding is near" heads-up is useless if Doze/battery-saving delays it by half an hour.
 */
object FeedReminder {

    const val CHANNEL_ID = "feed_reminders"
    const val NOTIFICATION_ID = 4202
    const val LEAD_MINUTES = 10L
    const val EXTRA_LAST_FEED = "feed_scheduled_for"
    private const val ALARM_REQUEST = 4202

    /** Schedule the reminder [delayMs] from now, tied to the [lastFeedTs] it was computed from. */
    fun schedule(context: Context, delayMs: Long, lastFeedTs: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(1_000L)
        val pi = alarmIntent(context, lastFeedTs)
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
        am.cancel(alarmIntent(context, 0L))
    }

    private fun alarmIntent(context: Context, lastFeedTs: Long): PendingIntent {
        val intent = Intent(context, FeedAlarmReceiver::class.java).apply {
            putExtra(EXTRA_LAST_FEED, lastFeedTs)
        }
        // Same request code -> FLAG_UPDATE_CURRENT refreshes the extra so a newer feed replaces
        // the pending alarm (and cancel() matches it by request code, ignoring the extra).
        return PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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

    /** Build + post the "feeding time is near" notification. Called from [FeedAlarmReceiver]. */
    suspend fun showReminder(context: Context, scheduledFor: Long) {
        val repo = CryRepository.get(context)
        val lastFeed = repo.lastFeedTimestamp()
        // A newer feed happened after we scheduled this (or the log was cleared): skip - a fresh
        // reminder was already queued for the new feed.
        if (lastFeed == null || lastFeed != scheduledFor) return

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        ensureChannel(context)

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
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(feedTitle())
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun feedTitle(): String = when (currentAppLang) {
        AppLang.EN -> "Feeding time is near \uD83C\uDF7C"
        AppLang.EL -> "Πλησιάζει η ώρα ταΐσματος \uD83C\uDF7C"
    }

    private fun feedBody(name: String): String = when (currentAppLang) {
        AppLang.EN -> "In about $LEAD_MINUTES minutes $name may get hungry. If a cry comes around now, hunger is the most likely reason."
        AppLang.EL -> "Σε περίπου $LEAD_MINUTES λεπτά μπορεί να πεινάσει ο/η $name. Αν κλάψει τώρα, πιο πιθανή αιτία είναι η πείνα."
    }
}

/** Receives the exact alarm and posts the feed reminder off the main thread. */
class FeedAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduledFor = intent.getLongExtra(FeedReminder.EXTRA_LAST_FEED, 0L)
        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                FeedReminder.showReminder(appContext, scheduledFor)
            } finally {
                result.finish()
            }
        }
    }
}

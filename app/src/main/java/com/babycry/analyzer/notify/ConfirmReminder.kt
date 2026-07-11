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
 * Schedules and shows the "why did the baby cry?" reminder a few minutes after a cry, so the
 * parent confirms the *real* reason once they've figured it out (fed and calmed = hunger,
 * burped = gas, etc.) rather than guessing the moment the cry is detected.
 *
 * We use an **exact alarm** rather than WorkManager: WorkManager is deferrable and gets batched
 * by Doze / OEM battery saving, so a "4 minutes later" job could actually fire tens of minutes
 * later. [AlarmManager.setExactAndAllowWhileIdle] fires on time even when the phone is asleep.
 */
object ConfirmReminder {

    const val CHANNEL_ID = "confirm_reminders"
    const val NOTIFICATION_ID = 4201
    const val EXTRA_OPEN_CONFIRM = "com.babycry.analyzer.OPEN_CONFIRM"
    private const val ALARM_REQUEST = 4201

    fun schedule(context: Context, delayMinutes: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerAt = System.currentTimeMillis() + delayMinutes.coerceAtLeast(1) * 60_000L
        val pi = alarmIntent(context)
        val canExact =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }.onFailure {
            // Exact-alarm access revoked at runtime -> still deliver, just not to-the-minute.
            runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi) }
        }
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(alarmIntent(context))
    }

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, ConfirmAlarmReceiver::class.java)
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
                    trS("Υπενθυμίσεις επιβεβαίωσης"),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = trS("Σε ρωτά λίγο μετά το κλάμα ποια ήταν τελικά η αιτία.")
                },
            )
        }
    }

    /** Build + post the "why did it cry?" notification. Called from [ConfirmAlarmReceiver]. */
    suspend fun showReminder(context: Context) {
        val repo = CryRepository.get(context)
        val pending = repo.pendingConfirmation() ?: return

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        ensureChannel(context)

        val profile = repo.getProfile()
        val name = if (profile.hasName) profile.name else trS("το μωρό")
        val predicted = repo.labels.getOrNull(pending.predictedIndex)
        val text = predicted?.let { notificationBody(trS(it.displayName)) }
            ?: trS("Πάτησε για να επιβεβαιώσεις γιατί έκλαψε.")

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_CONFIRM, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(notificationTitle(name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationTitle(name: String): String = when (currentAppLang) {
        AppLang.EN -> "Why did $name cry?"
        AppLang.EL -> "Γιατί έκλαψε $name;"
    }

    private fun notificationBody(displayName: String): String = when (currentAppLang) {
        AppLang.EN -> "Now that you know: was it \"$displayName\"? Tap to confirm or correct."
        AppLang.EL -> "Τώρα που ξέρεις: ήταν «$displayName»; Πάτησε για επιβεβαίωση ή διόρθωση."
    }
}

/** Receives the exact alarm and posts the reminder notification off the main thread. */
class ConfirmAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.Default).launch {
            try {
                ConfirmReminder.showReminder(appContext)
            } finally {
                result.finish()
            }
        }
    }
}

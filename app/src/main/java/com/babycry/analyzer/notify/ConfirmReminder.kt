package com.babycry.analyzer.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
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
 * Schedules and shows the "why did the baby cry?" reminder a few minutes after a cry, so the
 * parent confirms the *real* reason once they've figured it out (fed and calmed = hunger,
 * burped = gas, etc.) rather than guessing the moment the cry is detected.
 */
object ConfirmReminder {

    const val CHANNEL_ID = "confirm_reminders"
    const val NOTIFICATION_ID = 4201
    const val EXTRA_OPEN_CONFIRM = "com.babycry.analyzer.OPEN_CONFIRM"
    private const val WORK_NAME = "confirm_reminder"

    fun schedule(context: Context, delayMinutes: Long) {
        val request = OneTimeWorkRequestBuilder<ConfirmReminderWorker>()
            .setInitialDelay(delayMinutes.coerceAtLeast(1), TimeUnit.MINUTES)
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
                    trS("Υπενθυμίσεις επιβεβαίωσης"),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = trS("Σε ρωτά λίγο μετά το κλάμα ποια ήταν τελικά η αιτία.")
                },
            )
        }
    }
}

class ConfirmReminderWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = CryRepository.get(context)
        val pending = repo.pendingConfirmation() ?: return Result.success()

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return Result.success()
        }

        ConfirmReminder.ensureChannel(context)

        val profile = repo.getProfile()
        val name = if (profile.hasName) profile.name else trS("το μωρό")
        val predicted = repo.labels.getOrNull(pending.predictedIndex)
        val text = predicted?.let { notificationBody(trS(it.displayName)) }
            ?: trS("Πάτησε για να επιβεβαιώσεις γιατί έκλαψε.")

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(ConfirmReminder.EXTRA_OPEN_CONFIRM, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, ConfirmReminder.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(notificationTitle(name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(ConfirmReminder.NOTIFICATION_ID, notification)
        }
        return Result.success()
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

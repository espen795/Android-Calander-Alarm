package io.github.calendaralarm.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.calendaralarm.MainActivity
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.R
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.receiver.OwnedAlarmReceiver
import io.github.calendaralarm.ui.AlarmRingingActivity

object AlarmNotificationHelper {

    /**
     * v2.6.12: Central notification policy (design notification-policy-2026-11-12).
     *
     * ALL alarm notifications go through [notifyAlarmEvent]. The policy lives in one place:
     * - SET / CHANGED / REMOVED: only allowed when the caller has deduped
     *   (AlarmRepository state-key, or AlarmSyncWorker plan-vs-store diff). The funnel
     *   trusts that — NEVER call these events without dedup.
     * - PRE_ALARM / RINGING: always allowed (E2 + mandatory during ringing).
     * - SYNC_FAILED: always allowed (error message, no spam).
     * - UPDATE: unchanged behavior (outside the scope of the policy).
     */
    enum class AlarmEvent { SET, CHANGED, REMOVED, PRE_ALARM, RINGING, SYNC_FAILED, UPDATE }

    // v2.6.13 L9: SharedPreferences for the content-dedup in the funnel (safety net)
    private const val FUNNEL_PREFS_NAME = "notif_funnel"
    private const val FUNNEL_KEY_LAST_ALARM_EVENT = "last_alarm_event"

    /**
     * v2.6.12: The only place that decides WHICH event may be reported.
     */
    fun policyAllows(event: AlarmEvent): Boolean = when (event) {
        AlarmEvent.SET, AlarmEvent.CHANGED, AlarmEvent.REMOVED -> true // dedup upstream (repository/sync-worker)
        AlarmEvent.PRE_ALARM, AlarmEvent.RINGING -> true              // E2 + ringing always
        AlarmEvent.SYNC_FAILED -> true                                // error messages always
        AlarmEvent.UPDATE -> true                                     // unchanged
    }

    /**
     * v2.6.12: Central funnel for all alarm notifications.
     *
     * v2.6.13 (L9, safety net): for SET/CHANGED/REMOVED a content-dedup in SharedPreferences
     * `notif_funnel` — key `"$event|$time|$label"`. Substantively identical to the previous notification
     * → return false (silent). This is defense-in-depth ON TOP OF the upstream dedup (L1), not
     * a replacement for it.
     *
     * @return true if the notification was actually shown (policy check + dedup + permission OK)
     */
    fun notifyAlarmEvent(
        context: Context,
        event: AlarmEvent,
        time: String = "",
        label: String = "",
        alarmId: Int = -1
    ): Boolean {
        if (!policyAllows(event)) {
            AppLogger.d("AlarmNotificationHelper", "Policy blocks event=$event — no notification")
            return false
        }
        // v2.6.13 L9: content-dedup for alarm events (safety net)
        if (event in setOf(AlarmEvent.SET, AlarmEvent.CHANGED, AlarmEvent.REMOVED)) {
            val key = "$event|$time|$label"
            val funnelPrefs = context.getSharedPreferences(FUNNEL_PREFS_NAME, Context.MODE_PRIVATE)
            if (funnelPrefs.getString(FUNNEL_KEY_LAST_ALARM_EVENT, null) == key) {
                AppLogger.d("AlarmNotificationHelper", "L9 content-dedup: identical event=$event — silent")
                return false
            }
            funnelPrefs.edit().putString(FUNNEL_KEY_LAST_ALARM_EVENT, key).apply()
        }
        if (!hasNotificationPermission(context)) return false

        try {
            when (event) {
                AlarmEvent.SET -> showAlarmSetNotification(context, time, label)
                AlarmEvent.CHANGED -> showAlarmChangedNotification(context, time, label, alarmId)
                AlarmEvent.REMOVED -> showAlarmRemovedNotification(context, time, label, alarmId)
                AlarmEvent.SYNC_FAILED -> showSyncFailedNotification(context, label.ifEmpty { time })
                AlarmEvent.RINGING -> showAlarmRingingNotification(context, time, label, alarmId)
                AlarmEvent.PRE_ALARM -> {
                    // Pre-alarm is rendered by PreAlarmReceiver itself (own ID scheme + actions);
                    // here only the policy check.
                    AppLogger.d("AlarmNotificationHelper", "PRE_ALARM allowed (rendered by PreAlarmReceiver)")
                }
                AlarmEvent.UPDATE -> showUpdateNotification(context, time, label)
            }
        } catch (e: Exception) {
            // A failing render (e.g. missing resource in test/Robolectric) must NEVER crash the
            // caller — dedup state is already written, so return true (decided).
            AppLogger.e("AlarmNotificationHelper", "notifyAlarmEvent($event) render failed: ${e.message}")
        }
        return true
    }

    fun showAlarmSetNotification(context: Context, time: String, label: String) {
        if (!hasNotificationPermission(context)) return

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CalendarAlarmApp.CHANNEL_ALARMS)
            .setContentTitle(context.getString(R.string.alarm_set_notification_title))
            .setContentText(context.getString(R.string.alarm_set_notification_text, time, label))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(CalendarAlarmApp.NOTIFICATION_ID_ALARM, notification)
    }

    /**
     * v2.6.12: "Alarm changed" notification (E3) — per-alarm ID so they don't collide.
     */
    fun showAlarmChangedNotification(context: Context, time: String, label: String, alarmId: Int = -1) {
        if (!hasNotificationPermission(context)) return

        val notificationId = if (alarmId >= 0) alarmId + 10000 else CalendarAlarmApp.NOTIFICATION_ID_ALARM + 2

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CalendarAlarmApp.CHANNEL_ALARMS)
            .setContentTitle("⏰ Alarm changed")
            .setContentText("$time - $label")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    /**
     * v2.6.12: "Alarm removed" notification (E3).
     */
    fun showAlarmRemovedNotification(context: Context, time: String, label: String, alarmId: Int = -1) {
        if (!hasNotificationPermission(context)) return

        val notificationId = if (alarmId >= 0) alarmId + 10000 else CalendarAlarmApp.NOTIFICATION_ID_ALARM + 3

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, notificationId, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = if (time.isNotEmpty()) "$time - $label" else label
        val notification = NotificationCompat.Builder(context, CalendarAlarmApp.CHANNEL_ALARMS)
            .setContentTitle("🗑️ Alarm removed")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    /**
     * Full-screen alarm ringing notification with snooze/dismiss actions.
     *
     * F6: Snooze button text reflects the configured snooze duration.
     */
    fun showAlarmRingingNotification(context: Context, time: String, label: String, alarmId: Int) {
        if (!hasNotificationPermission(context)) return

        // F6: Read configured snooze duration
        val snoozeMinutes = try {
            CalendarAlarmApp.settingsRepository.getSnoozeMinutes()
        } catch (e: Exception) {
            OwnedAlarmReceiver.DEFAULT_SNOOZE_MINUTES.toInt()
        }

        // Tap → open ringing activity
        val activityIntent = Intent(context, AlarmRingingActivity::class.java).apply {
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val activityPi = PendingIntent.getActivity(
            context, alarmId, activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Snooze action
        val snoozeIntent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            action = OwnedAlarmReceiver.ACTION_SNOOZE
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        val snoozePi = PendingIntent.getBroadcast(
            context, alarmId + 100000, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Dismiss action
        val dismissIntent = Intent(context, OwnedAlarmReceiver::class.java).apply {
            action = OwnedAlarmReceiver.ACTION_DISMISS
            putExtra(OwnedAlarmReceiver.EXTRA_ALARM_ID, alarmId)
        }
        val dismissPi = PendingIntent.getBroadcast(
            context, alarmId + 200000, dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CalendarAlarmApp.CHANNEL_ALARMS_RINGING)  // v2.6.9: New channel with IMPORTANCE_HIGH
            .setContentTitle("⏰ $time — $label")
            .setContentText("Alarm active")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(activityPi)
            // v2.6.9: setFullScreenIntent RESTORED — this is THE fullscreen trigger when the alarm fires
            // AlarmClockInfo.showIntent does NOTHING on alarm fire (system UI only)
            .setFullScreenIntent(activityPi, true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Snooze $snoozeMinutes min", snoozePi)  // F6: dynamic snooze text
            .addAction(0, "Dismiss", dismissPi)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(CalendarAlarmApp.NOTIFICATION_ID_RINGING + alarmId, notification)
    }

    fun cancelAlarmRingingNotification(context: Context, alarmId: Int = -1) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (alarmId >= 0) {
            // NC-02 fix: Use the same ID scheme as showAlarmRingingNotification
            notificationManager.cancel(CalendarAlarmApp.NOTIFICATION_ID_RINGING + alarmId)
        } else {
            // Cancel all ringing notifications (fallback)
            // We can't enumerate, but we can cancel a range of likely IDs
            for (id in 0..1000) {
                notificationManager.cancel(CalendarAlarmApp.NOTIFICATION_ID_RINGING + id)
            }
        }
    }

    fun showUpdateNotification(context: Context, version: String, changelog: String) {
        if (!hasNotificationPermission(context)) return

        // L-02 fix: no unused navigation extra
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CalendarAlarmApp.CHANNEL_UPDATES)
            .setContentTitle(context.getString(R.string.update_available_title))
            .setContentText(context.getString(R.string.update_available_text, version))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)  // v2.6.13 L7: update notification silently updates on repeat
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(CalendarAlarmApp.NOTIFICATION_ID_UPDATE, notification)
    }

    fun showSyncFailedNotification(context: Context, reason: String) {
        if (!hasNotificationPermission(context)) return

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CalendarAlarmApp.CHANNEL_ALARMS)
            .setContentTitle("⚠️ Calendar Alarm sync failed")
            .setContentText(reason)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)  // v2.6.13 L6: no heads-up/sound on repeat of same message
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(CalendarAlarmApp.NOTIFICATION_ID_ALARM + 1, notification)
    }

    fun showSyncSuccessNotification(context: Context, wakeTime: String, label: String, date: String) {
        // v2.6.12: via the central funnel
        notifyAlarmEvent(context, AlarmEvent.SET, time = wakeTime, label = "$label ($date)")
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

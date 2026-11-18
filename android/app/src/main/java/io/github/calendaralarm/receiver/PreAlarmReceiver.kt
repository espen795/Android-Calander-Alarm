package io.github.calendaralarm.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.calendaralarm.MainActivity
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.R
import io.github.calendaralarm.data.AlarmRepository
import io.github.calendaralarm.data.AlarmStore
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.service.AlarmNotificationHelper

/**
 * F4: Pre-alarm notification receiver.
 *
 * Fires 60 minutes before an alarm is scheduled to ring, showing a notification with:
 * - "Dismiss" button: cancels the alarm immediately
 * - "Skip this time" button: skips this occurrence (only for repeat alarms with repeatDays)
 *
 * The notification is posted on the alarms channel with high priority.
 */
class PreAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PreAlarmReceiver"
        const val ACTION_TURN_OFF = "io.github.calendaralarm.action.PRE_ALARM_TURN_OFF"
        const val ACTION_SKIP = "io.github.calendaralarm.action.PRE_ALARM_SKIP"
        const val EXTRA_ALARM_ID = AlarmRepository.EXTRA_ALARM_ID

        // Notification ID offset for pre-alarm notifications (avoid collision with ringing notifications)
        private const val NOTIFICATION_ID_OFFSET = 700000
    }

    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1)
        if (alarmId == -1) {
            AppLogger.w(TAG, "Received intent without alarm id")
            return
        }

        AppLogger.i(TAG, "Pre-alarm received: action=${intent.action}, alarmId=$alarmId")

        when (intent.action) {
            ACTION_TURN_OFF -> handleTurnOff(context, alarmId)
            ACTION_SKIP -> handleSkip(context, alarmId)
            else -> showPreAlarmNotification(context, alarmId)
        }
    }

    /**
     * Show the pre-alarm notification (default action when the pre-alarm fires).
     * v2.6.12: E2 (pre-alarm 60 min) — central policy check via the funnel.
     */
    private fun showPreAlarmNotification(context: Context, alarmId: Int) {
        // v2.6.12: PRE_ALARM is always allowed, but the decision now lives in one place
        if (!AlarmNotificationHelper.policyAllows(AlarmNotificationHelper.AlarmEvent.PRE_ALARM)) {
            AppLogger.d(TAG, "Pre-alarm blocked by central policy")
            return
        }
        if (!hasNotificationPermission(context)) return

        val repo = AlarmRepository(context)
        val alarm = repo.store.get(alarmId)
        if (alarm == null) {
            AppLogger.w(TAG, "Pre-alarm: alarm $alarmId not found in store")
            return
        }

        if (!alarm.enabled) {
            AppLogger.d(TAG, "Pre-alarm: alarm $alarmId is disabled, skipping notification")
            return
        }

        // Tap → open main activity
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPi = PendingIntent.getActivity(
            context, alarmId, mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Dismiss" action → cancel the alarm
        val turnOffIntent = Intent(context, PreAlarmReceiver::class.java).apply {
            action = ACTION_TURN_OFF
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        val turnOffPi = PendingIntent.getBroadcast(
            context, alarmId + 600000, turnOffIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CalendarAlarmApp.CHANNEL_ALARMS)
            .setContentTitle("⏰ Alarm in 1 hour: ${alarm.timeLabel} — ${alarm.label}")
            .setContentText("The alarm will ring at ${alarm.timeLabel}")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(mainPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Dismiss", turnOffPi)

        // F5: "Skip this time" — only for repeat alarms
        if (alarm.repeatDays.isNotEmpty()) {
            val skipIntent = Intent(context, PreAlarmReceiver::class.java).apply {
                action = ACTION_SKIP
                putExtra(EXTRA_ALARM_ID, alarmId)
            }
            val skipPi = PendingIntent.getBroadcast(
                context, alarmId + 610000, skipIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "Skip this time", skipPi)
        }

        val notification = builder.build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID_OFFSET + alarmId, notification)

        AppLogger.i(TAG, "Pre-alarm notification shown for alarm id=$alarmId (${alarm.timeLabel})")
    }

    /**
     * "Dismiss" button: cancel the alarm immediately.
     */
    private fun handleTurnOff(context: Context, alarmId: Int) {
        val repo = AlarmRepository(context)
        val alarm = repo.store.get(alarmId)

        if (alarm != null) {
            AppLogger.i(TAG, "Pre-alarm 'Dismiss': cancelling alarm id=$alarmId (${alarm.timeLabel})")
            // v2.6.13 L8: user tapped Dismiss themselves → force=true (no "removed" push)
            repo.cancel(alarmId, force = true)
        } else {
            repo.cancel(alarmId, force = true) // best effort
        }

        // Cancel the pre-alarm notification
        cancelNotification(context, alarmId)
    }

    /**
     * FC-02 fix: "Skip this time" button.
     *
     * CRITICAL FIX: Previously this called setEnabled(false) which set enabled=false
     * in the store. But scheduleOneShot() searches for skip alarms with filter
     * `it.enabled` — so a disabled alarm was NEVER found and skipCount was never
     * consumed. The alarm was permanently disabled.
     *
     * Now: we ONLY cancel the AlarmManager entry (main + pre-alarm), keeping the
     * alarm enabled=true in the store. The skip is consumed on the next schedule
     * via scheduleOneShot() which finds the enabled alarm with skipCount > 0.
     *
     * Additionally, we schedule the next occurrence immediately for repeat alarms,
     * so the alarm is re-armed for the next matching day right away.
     */
    private fun handleSkip(context: Context, alarmId: Int) {
        val repo = AlarmRepository(context)
        val alarm = repo.store.get(alarmId)

        if (alarm == null) {
            AppLogger.w(TAG, "Pre-alarm 'Skip': alarm $alarmId not found")
            cancelNotification(context, alarmId)
            return
        }

        if (alarm.repeatDays.isEmpty()) {
            AppLogger.w(TAG, "Pre-alarm 'Skip': alarm $alarmId is not a repeat alarm, ignoring skip")
            cancelNotification(context, alarmId)
            return
        }

        // FC-02: Increment skip count (alarm stays enabled in store!)
        val updated = repo.store.incrementSkipCount(alarmId)
        val newSkipCount = updated?.skipCount ?: (alarm.skipCount + 1)
        AppLogger.i(TAG, "FC-02 Pre-alarm 'Skip this time': alarm id=$alarmId (${alarm.timeLabel}), skipCount=$newSkipCount")

        // FC-02: Cancel ONLY the AlarmManager entry — do NOT set enabled=false!
        // The alarm must stay enabled in the store so scheduleOneShot() can find it
        // and consume the skipCount on the next scheduling cycle.
        repo.cancelAlarmManagerOnly(alarmId, alarm.preAlarmId)

        // FC-02: Schedule the next occurrence immediately for repeat alarms
        if (alarm.repeatDays.isNotEmpty()) {
            val nextTrigger = repo.computeNextTriggerAfterToday(alarm.timeLabel, alarm.repeatDays)
            if (nextTrigger != null) {
                val zone = java.time.ZoneId.systemDefault()
                val nextDate = java.time.LocalDate.ofInstant(nextTrigger, zone).toString()
                // v2.6.13 C1 FIX: the skip is consumed HERE — the record moves
                // directly to the next occurrence and skipCount goes back to 0.
                // Before this fix skipCount=1 stayed in the record; handleAlarmFired
                // had the guard `skipCount == 0` and therefore NEVER re-armed anymore, which
                // silently broke the repeat chain after one skip. Now the trigger is already shifted
                // and skipCount is 0 again, so the next occurrence re-arms normally.
                val rescheduled = alarm.copy(
                    triggerAtMillis = nextTrigger.toEpochMilli(),
                    date = nextDate,
                    enabled = true,
                    skipCount = 0  // v2.6.13 C1: consume the skip here (trigger already shifted)
                )
                repo.schedule(rescheduled, force = true)  // system action (skip) → silent
                AppLogger.i(TAG, "v2.6.13 C1 Skip: rescheduled alarm id=$alarmId for $nextDate at ${alarm.timeLabel} (skipCount consumed → 0)")
            }
        }

        // Cancel the pre-alarm notification
        cancelNotification(context, alarmId)
    }

    private fun cancelNotification(context: Context, alarmId: Int) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID_OFFSET + alarmId)
    }

    private fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

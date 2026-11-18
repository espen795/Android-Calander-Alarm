package io.github.calendaralarm.receiver

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.calendaralarm.MainActivity
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.R
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.TimerAlarmHelper
import io.github.calendaralarm.data.TimerStateStore
import io.github.calendaralarm.service.TimerForegroundService
import io.github.calendaralarm.ui.TimerRingingActivity

private const val TAG = "TimerFireReceiver"

/**
 * v2.5.6 Story 3: BroadcastReceiver that fires when the countdown timer expires.
 *
 * Triggered by AlarmManager (TimerScheduler) at the moment the timer
 * expires — even when the app is killed or the device has rebooted.
 *
 * Responsibilities:
 * - Start TimerAlarmHelper shared instance alarm (sound + vibration + wakelock)
 * - Launch TimerRingingActivity (full-screen, over lock screen)
 * - Post notification as backup (in case the activity fails)
 * - Clear TimerState (timer is done)
 *
 * Samsung S24 compatibility:
 * - WakeLock to keep the device awake during handling
 * - USE_FULL_SCREEN_INTENT for the full-screen activity (already declared)
 * - Notification as fallback when the activity cannot start
 */
class TimerFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.i(TAG, "TimerFireReceiver received: action=${intent.action}")

        if (intent.action != io.github.calendaralarm.data.TimerScheduler.ACTION_TIMER_FIRED) {
            AppLogger.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }

        try {
            // 1. Wake lock — device must stay awake for alarm handling
            acquireWakeLock(context)

            // 2. Start alarm sound + vibration via TimerAlarmHelper shared instance
            val alarmHelper = TimerAlarmHelper.getInstance(context)
            alarmHelper.startAlarm()
            AppLogger.i(TAG, "Timer alarm started (sound + vibration + wakelock)")

            // 3. Launch TimerRingingActivity (full-screen)
            launchRingingActivity(context)

            // 4. Post notification as backup
            showTimerFiredNotification(context)

            // 5. Clear TimerState — timer is done
            TimerStateStore(context).clear()
            AppLogger.i(TAG, "TimerState cleared — timer finished")

            // v2.5.6 PM fix C-01: Stop foreground service — voorkomt battery drain + ANR risk
            // (service bleef draaien met START_STICKY zelfs nadat timer afliep)
            TimerForegroundService.stop(context)
            AppLogger.i(TAG, "TimerForegroundService stopped")

        } catch (e: Exception) {
            AppLogger.e(TAG, "Error handling timer fire: ${e.message}")
        }
    }

    /**
     * Acquire a wake lock so the device stays awake during alarm handling.
     * Release happens automatically after 5 minutes (safety timeout) or when
     * TimerAlarmHelper.stopAlarm() is called.
     */
    private fun acquireWakeLock(context: Context) {
        try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CalendarAlarm:TimerFireWakeLock"
            )
            @Suppress("WakelockTimeout")
            wakeLock.acquire(5 * 60 * 1000L) // 5 min max — safety timeout
            AppLogger.i(TAG, "WakeLock acquired for timer fire")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to acquire WakeLock: ${e.message}")
        }
    }

    /**
     * Launch the TimerRingingActivity (full-screen, over lock screen).
     */
    private fun launchRingingActivity(context: Context) {
        try {
            val activityIntent = Intent(context, TimerRingingActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                        or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(activityIntent)
            AppLogger.i(TAG, "TimerRingingActivity launched")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to launch TimerRingingActivity: ${e.message}")
            // Notification was already posted as backup — the user can stop the timer via the notification
        }
    }

    /**
     * Post a notification as backup — so the user can stop the timer
     * even when the activity was not started.
     */
    private fun showTimerFiredNotification(context: Context) {
        try {
            // Check notification permission (Android 13+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasPermission) {
                    AppLogger.w(TAG, "No POST_NOTIFICATIONS permission — skipping timer fired notification")
                    return
                }
            }

            // Tap → open TimerRingingActivity
            val activityIntent = Intent(context, TimerRingingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val activityPi = PendingIntent.getActivity(
                context,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Stop action → broadcast to TimerActionReceiver
            val stopIntent = Intent(context, TimerActionReceiver::class.java).apply {
                action = TimerActionReceiver.ACTION_STOP_TIMER
            }
            val stopPi = PendingIntent.getBroadcast(
                context,
                0,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Tap → open MainActivity (fallback)
            val mainIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val mainPi = PendingIntent.getActivity(
                context,
                1,
                mainIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CalendarAlarmApp.CHANNEL_TIMER)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("⏰ Timer expired")
                .setContentText("Tap to stop")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(activityPi)
                .setFullScreenIntent(activityPi, true) // Full-screen intent for lock screen
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
                .setAutoCancel(true)
                .build()

            // v2.5.6 PM fix C-02: Aparte notification ID voor fired state (was NOTIFICATION_ID_TIMER)
            NotificationManagerCompat.from(context).notify(
                CalendarAlarmApp.NOTIFICATION_ID_TIMER_FIRED,
                notification
            )
            AppLogger.i(TAG, "Timer fired notification posted")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to post timer fired notification: ${e.message}")
        }
    }
}

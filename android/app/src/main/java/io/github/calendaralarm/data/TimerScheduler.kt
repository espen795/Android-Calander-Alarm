package io.github.calendaralarm.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.calendaralarm.receiver.TimerFireReceiver

private const val TAG = "TimerScheduler"

/**
 * v2.5.6 Story 2: AlarmManager integration for the countdown timer.
 *
 * Like AlarmRepository for alarms, this class uses AlarmManager to
 * let the timer fire at an exact moment — even when the app is killed.
 *
 * Key choices:
 * - setExactAndAllowWhileIdle(RTC_WAKEUP) — works in Doze mode, requires SCHEDULE_EXACT_ALARM
 * - RTC_WAKEUP (not ELAPSED_REALTIME_WAKEUP) — wall clock time survives reboot
 * - requestCode 9999 (unique, not conflicting with alarm IDs)
 * - FLAG_IMMUTABLE — required on Android 12+
 * - PendingIntent to TimerFireReceiver (BroadcastReceiver)
 *
 * Samsung S24 / Android 15 compatibility:
 * - setExactAndAllowWhileIdle works on Samsung with the SCHEDULE_EXACT_ALARM permission
 * - RTC_WAKEUP is more reliable than ELAPSED_REALTIME for reboot recovery
 */
class TimerScheduler(context: Context) {

    private val appContext: Context = context.applicationContext

    private val alarmManager: AlarmManager by lazy {
        val service = appContext.getSystemService(Context.ALARM_SERVICE)
        requireNotNull(service as? AlarmManager) {
            "AlarmManager service not available"
        }
    }

    companion object {
        /** Unique request code for the timer PendingIntent (not conflicting with alarm IDs) */
        const val TIMER_REQUEST_CODE = 9999

        /** Action string for the timer fired broadcast */
        const val ACTION_TIMER_FIRED = "io.github.calendaralarm.action.TIMER_FIRED"
    }

    /**
     * Check whether we may schedule exact alarms (Android 12+ / Samsung S24).
     * Requires the SCHEDULE_EXACT_ALARM permission (already declared in the manifest).
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * Schedule the timer to fire at [endTimeWallClockMs].
     *
     * @param endTimeWallClockMs Wall clock timestamp (System.currentTimeMillis based)
     *                           when the timer must fire
     * @return true if the alarm was successfully scheduled
     */
    fun schedule(endTimeWallClockMs: Long): Boolean {
        if (!canScheduleExactAlarms()) {
            AppLogger.e(TAG, "Cannot schedule timer: exact alarm permission NOT granted")
            return false
        }

        // Reject past times — timer already finished
        val now = io.github.calendaralarm.data.AppClock.now()
        if (endTimeWallClockMs <= now) {
            AppLogger.w(TAG, "Timer end time is in the past (${endTimeWallClockMs} <= $now), not scheduling")
            return false
        }

        val intent = Intent(appContext, TimerFireReceiver::class.java).apply {
            action = ACTION_TIMER_FIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            TIMER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            // setExactAndAllowWhileIdle — works in Doze, Samsung-compatible
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                endTimeWallClockMs,
                pendingIntent
            )
            val remainingMs = endTimeWallClockMs - now
            AppLogger.i(TAG, "Timer scheduled: fires in ${remainingMs}ms (at $endTimeWallClockMs)")
            return true
        } catch (e: SecurityException) {
            AppLogger.e(TAG, "SecurityException scheduling timer: ${e.message}")
            return false
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to schedule timer: ${e.message}")
            return false
        }
    }

    /**
     * Cancel the timer alarm (on pause, stop, reset, or a new timer start).
     */
    fun cancel() {
        val intent = Intent(appContext, TimerFireReceiver::class.java).apply {
            action = ACTION_TIMER_FIRED
        }
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            TIMER_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.cancel(pendingIntent)
            AppLogger.i(TAG, "Timer alarm cancelled")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error cancelling timer alarm: ${e.message}")
        }
    }

    /**
     * Check whether a timer alarm is scheduled.
     * Note: Android has no direct API to check whether a PendingIntent exists.
     * We check the TimerStateStore instead.
     */
    fun isScheduled(context: Context): Boolean {
        return TimerStateStore(context).hasActiveTimer()
    }
}

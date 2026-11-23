package io.github.calendaralarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import io.github.calendaralarm.CalendarAlarmApp
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.TimerAlarmHelper
import io.github.calendaralarm.data.TimerScheduler
import io.github.calendaralarm.data.TimerStateStore
import io.github.calendaralarm.service.TimerForegroundService

private const val TAG = "TimerActionReceiver"

/**
 * v2.5.3 Story 4: Broadcast receiver for timer notification actions.
 *
 * Handles the "Stop" action from the ongoing timer notification.
 * Stops the timer alarm sound/vibration via the TimerAlarmHelper shared instance.
 *
 * v2.5.6: Also clears TimerStateStore + cancels AlarmManager + stops the foreground service.
 * This ensures a stopped timer does not fire again after an app restart.
 */
class TimerActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_STOP_TIMER = "io.github.calendaralarm.action.STOP_TIMER"
    }

    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.i(TAG, "Received intent: action=${intent.action}")

        when (intent.action) {
            ACTION_STOP_TIMER -> {
                AppLogger.i(TAG, "v2.6.8: Stop timer action received — stopping alarm")
                
                // v2.7.0: Stop the ringing via the single owner (TimerRingService)
                io.github.calendaralarm.service.TimerRingService.stop(context)
                
                // v2.6.8: Clear TimerState + cancel AlarmManager + stop foreground service
                try {
                    val stateStore = TimerStateStore(context)
                    val scheduler = TimerScheduler(context)
                    
                    AppLogger.i(TAG, "v2.6.8: Clearing TimerStateStore...")
                    stateStore.clear()
                    
                    AppLogger.i(TAG, "v2.6.8: Cancelling AlarmManager timer...")
                    scheduler.cancel()
                    
                    AppLogger.i(TAG, "v2.6.8: Stopping TimerForegroundService...")
                    TimerForegroundService.stop(context)
                    
                    AppLogger.i(TAG, "v2.6.8: Timer state cleared, alarm cancelled, service stopped")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "v2.6.8: Failed to clean up timer state: ${e.message}")
                }

                // v2.6.8: Cancel ALL timer notifications (multiple IDs possible)
                try {
                    val nm = NotificationManagerCompat.from(context)
                    // Cancel all possible timer notification IDs
                    nm.cancel(CalendarAlarmApp.NOTIFICATION_ID_TIMER)           // 5001 - TimerScreen UI
                    nm.cancel(CalendarAlarmApp.NOTIFICATION_ID_TIMER_SERVICE)   // 5002 - ForegroundService
                    nm.cancel(CalendarAlarmApp.NOTIFICATION_ID_TIMER_FIRED)     // 5003 - TimerFireReceiver
                    AppLogger.i(TAG, "v2.6.8: All timer notifications cancelled (5001, 5002, 5003)")
                } catch (e: Exception) {
                    AppLogger.e(TAG, "v2.6.8: Failed to cancel notification: ${e.message}")
                }
                
                // v2.6.8: Show toast for confirmation
                android.widget.Toast.makeText(context, "⏱️ Timer stopped", android.widget.Toast.LENGTH_SHORT).show()
            }
            else -> {
                AppLogger.w(TAG, "v2.6.8: Unknown action: ${intent.action}")
            }
        }
    }
}

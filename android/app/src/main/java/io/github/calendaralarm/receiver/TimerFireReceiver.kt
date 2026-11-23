package io.github.calendaralarm.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.calendaralarm.data.AppLogger
import io.github.calendaralarm.data.TimerScheduler
import io.github.calendaralarm.data.TimerStateStore
import io.github.calendaralarm.service.TimerForegroundService
import io.github.calendaralarm.service.TimerRingService

private const val TAG = "TimerFireReceiver"

/**
 * Receives the AlarmManager trigger when the countdown timer expires (also after app kill or reboot).
 *
 * v2.7.0: All paths that detect the end of the timer (this receiver, the ViewModel loop,
 * BootReceiver) go through [fire]. That claims the timer atomically in TimerStateStore, so
 * ringing starts exactly once. The ringing itself (sound, vibration, full-screen activity)
 * is fully handled in [TimerRingService].
 */
class TimerFireReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TimerScheduler.ACTION_TIMER_FIRED) {
            AppLogger.w(TAG, "Ignoring unexpected action: ${intent.action}")
            return
        }
        fire(context, source = "alarmmanager")
    }

    companion object {
        /**
         * Let the timer fire — idempotent.
         *
         * @return true if this call started the ringing, false if another path
         *         already handled the timer (or there was no running timer).
         */
        fun fire(context: Context, source: String): Boolean {
            val appContext = context.applicationContext
            val claimed = TimerStateStore(appContext).claimFinished()
            if (!claimed) {
                AppLogger.i(TAG, "fire($source): timer already handled — nothing to do")
                return false
            }
            AppLogger.i(TAG, "fire($source): timer finished → ringing")
            TimerScheduler(appContext).cancel()
            TimerForegroundService.stop(appContext)
            TimerRingService.ring(appContext)
            return true
        }
    }
}
